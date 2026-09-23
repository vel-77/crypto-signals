package com.cryptosignals.marketdata;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.LevelAnalysisEngineFactory;
import com.cryptosignals.analysis.engine.EngineUpdate;
import com.cryptosignals.analysis.model.TradingSignal;
import com.cryptosignals.config.AnalysisProperties;
import com.cryptosignals.config.ExchangeProperties;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.exchange.ExchangeClient;
import com.cryptosignals.notifications.NotificationService;
import com.cryptosignals.persistence.AnalysisResultStore;
import com.cryptosignals.persistence.entity.MarketEntity;
import com.cryptosignals.persistence.jdbc.CandleJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns one analysis engine per market and drives candles through it.
 *
 * <h2>Engine state is derived, never persisted</h2>
 * On startup each market's engine is rebuilt from scratch by replaying stored candles. Nothing
 * about the engine's internal state machines is serialised. That is a deliberate trade: replay
 * costs a few seconds, and in exchange a restart can never resume from a half-written or
 * version-skewed snapshot, and the running system is always exactly what the configured rules say
 * it should be. Because ids are content-derived, the replay converges on the rows already in the
 * database instead of duplicating them.
 */
@Service
public class MarketAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(MarketAnalysisService.class);

    /** Candles pulled per page during replay; bounds memory without a long-lived transaction. */
    private static final int REPLAY_PAGE_SIZE = 5_000;

    private final LevelAnalysisEngineFactory engineFactory;
    private final AnalysisProperties analysisProperties;
    private final ExchangeProperties exchangeProperties;
    private final CandleJdbcRepository candles;
    private final AnalysisResultStore store;
    private final NotificationService notifications;
    private final MarketDataService marketData;
    private final ExchangeClient exchange;

    private final Map<MarketKey, MarketEngine> engines = new ConcurrentHashMap<>();

    public MarketAnalysisService(LevelAnalysisEngineFactory engineFactory,
                                 AnalysisProperties analysisProperties,
                                 ExchangeProperties exchangeProperties,
                                 CandleJdbcRepository candles,
                                 AnalysisResultStore store,
                                 NotificationService notifications,
                                 MarketDataService marketData,
                                 ExchangeClient exchange) {
        this.engineFactory = engineFactory;
        this.analysisProperties = analysisProperties;
        this.exchangeProperties = exchangeProperties;
        this.candles = candles;
        this.store = store;
        this.notifications = notifications;
        this.marketData = marketData;
        this.exchange = exchange;
    }

    public AnalysisConfig currentConfig() {
        return analysisProperties.toAnalysisConfig();
    }

    /** Creates (or returns) the engine for a market, registering the market row if new. */
    public MarketEngine initialise(MarketKey key) {
        return engines.computeIfAbsent(key, k -> {
            MarketEntity market = store.resolveMarket(exchangeProperties.getName(), k);
            // Live engines drop completed levels once reported: those rows already live in the
            // database, and holding them forever would make memory grow without bound.
            var engine = engineFactory.create(k, currentConfig(), false);
            log.info("Initialised engine for {} (strategy: {}, config: {})",
                    k, engineFactory.detectionStrategyName(), currentConfig().fingerprint());
            return new MarketEngine(k, market, engine);
        });
    }

    /**
     * Replays stored candles through a freshly built engine.
     *
     * <p>Paged rather than streamed in one transaction: writes produced by the replay need their
     * own transactions, and a read-only snapshot held open for half a million rows would pin the
     * database's vacuum horizon for the whole startup.
     */
    public long replayHistory(MarketEngine marketEngine) {
        MarketKey key = marketEngine.key();
        long marketId = marketEngine.market().getId();
        Instant cursor = marketData.analysisStart();
        Instant to = Instant.now();
        long step = key.timeframe().durationMillis();
        long processed = 0;
        int signalsFound = 0;

        long startedAt = System.currentTimeMillis();
        while (true) {
            List<Candle> page = candles.findRange(marketId, cursor, to, REPLAY_PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (Candle candle : page) {
                EngineUpdate update = marketEngine.process(candle);
                persist(marketEngine, update);
                signalsFound += update.newSignals().size();
                processed++;
            }
            if (page.size() < REPLAY_PAGE_SIZE) {
                break;
            }
            cursor = Instant.ofEpochMilli(page.get(page.size() - 1).openTime() + step);
        }

        marketEngine.switchToLive();
        log.info("Replayed {} candles for {} in {} ms: {} historical signal(s)",
                processed, key, System.currentTimeMillis() - startedAt, signalsFound);
        return processed;
    }

    /**
     * Feeds one closed candle arriving live.
     *
     * <p>If the candle is not the immediate successor of the last one processed, the missing span is
     * pulled from the exchange and replayed first. That is what makes a websocket reconnect
     * lossless: the socket only has to come back, and this closes whatever hole it left behind.
     */
    public void ingestLiveCandle(MarketKey key, Candle candle) {
        MarketEngine marketEngine = engines.get(key);
        if (marketEngine == null) {
            log.warn("Received a candle for unregistered market {}; ignoring", key);
            return;
        }
        try {
            marketData.storeLiveCandle(marketEngine.market(), candle);
            fillGapBefore(marketEngine, candle);

            EngineUpdate update = marketEngine.process(candle);
            persist(marketEngine, update);
            for (TradingSignal signal : update.newSignals()) {
                notifications.dispatch(signal);
            }
        } catch (Exception ex) {
            // A single bad candle must never kill the stream for every other market.
            log.error("Failed to ingest candle for {} at {}: {}",
                    key, Instant.ofEpochMilli(candle.openTime()), ex.toString(), ex);
        }
    }

    private void fillGapBefore(MarketEngine marketEngine, Candle candle) {
        long last = marketEngine.lastCandleOpenTime();
        long step = marketEngine.key().timeframe().durationMillis();
        if (last < 0 || candle.openTime() <= last + step) {
            return;
        }

        long missing = (candle.openTime() - last) / step - 1;
        log.info("Detected {} missing candle(s) for {} before {}; refetching",
                missing, marketEngine.key(), Instant.ofEpochMilli(candle.openTime()));

        List<Candle> recovered = exchange.fetchCandles(marketEngine.key().symbol(),
                marketEngine.key().timeframe(),
                Instant.ofEpochMilli(last + step),
                Instant.ofEpochMilli(candle.openTime() - step),
                (int) Math.min(missing + 10, 10_000));

        if (!recovered.isEmpty()) {
            candles.upsert(marketEngine.market().getId(), recovered);
        }
        for (Candle gapCandle : recovered) {
            EngineUpdate update = marketEngine.process(gapCandle);
            persist(marketEngine, update);
            for (TradingSignal signal : update.newSignals()) {
                notifications.dispatch(signal);
            }
        }
    }

    private void persist(MarketEngine marketEngine, EngineUpdate update) {
        store.apply(marketEngine.market(), update,
                engineFactory.detectionStrategyName(), currentConfig().fingerprint());
    }

    public Optional<MarketEngine> engine(MarketKey key) {
        return Optional.ofNullable(engines.get(key));
    }

    public Collection<MarketEngine> allEngines() {
        return engines.values();
    }
}
