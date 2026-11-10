package com.cryptosignals.marketdata;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.LevelAnalysisEngineFactory;
import com.cryptosignals.analysis.engine.EngineUpdate;
import com.cryptosignals.analysis.model.TradingSignal;
import com.cryptosignals.config.AnalysisProperties;
import com.cryptosignals.config.ExchangeProperties;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.exchange.ExchangeClient;
import com.cryptosignals.notifications.NotificationService;
import com.cryptosignals.persistence.AnalysisResultStore;
import com.cryptosignals.persistence.entity.MarketEntity;
import com.cryptosignals.persistence.jdbc.CandleJdbcRepository;
import com.cryptosignals.persistence.repository.MarketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final NextPotentialService nextPotential;
    private final MarketRepository markets;

    private final Map<MarketKey, MarketEngine> engines = new ConcurrentHashMap<>();

    public MarketAnalysisService(LevelAnalysisEngineFactory engineFactory,
                                 AnalysisProperties analysisProperties,
                                 ExchangeProperties exchangeProperties,
                                 CandleJdbcRepository candles,
                                 AnalysisResultStore store,
                                 NotificationService notifications,
                                 MarketDataService marketData,
                                 ExchangeClient exchange,
                                 NextPotentialService nextPotential,
                                 MarketRepository markets) {
        this.engineFactory = engineFactory;
        this.analysisProperties = analysisProperties;
        this.exchangeProperties = exchangeProperties;
        this.candles = candles;
        this.store = store;
        this.notifications = notifications;
        this.marketData = marketData;
        this.exchange = exchange;
        this.nextPotential = nextPotential;
        this.markets = markets;
    }

    /** The rules for one market; the supersede distance depends on its timeframe. */
    public AnalysisConfig configFor(MarketKey key) {
        return analysisProperties.toAnalysisConfig(key.timeframe());
    }

    /** Creates (or returns) the engine for a market, registering the market row if new. */
    public MarketEngine initialise(MarketKey key) {
        return engines.computeIfAbsent(key, k -> {
            MarketEntity market = store.resolveMarket(exchangeProperties.getName(), k);
            // Live engines drop completed levels once reported: those rows already live in the
            // database, and holding them forever would make memory grow without bound.
            AnalysisConfig config = configFor(k);
            var engine = engineFactory.create(k, config, false);
            log.info("Initialised engine for {} (strategy: {}, config: {}, supersede distance: {}%)",
                    k, engineFactory.detectionStrategyName(), config.fingerprint(),
                    config.supersedeMaxDistancePercent());
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

    /**
     * Feeds stored candles the engine has not processed yet.
     *
     * <p>Reconciliation downloads candles the live stream missed, but storing them is not enough:
     * the engine only learns about a candle when it is fed one. Without this, a stream that died
     * silently would leave the database complete and the analysis frozen at the moment the stream
     * stopped — no new levels, touches or signals until the next restart.
     *
     * <p>Safe to run while the live stream is also delivering: the engine processes candles one at
     * a time under the market's lock and ignores any it has already seen, so the two paths merge
     * into a single ascending sequence.
     *
     * @return how many candles were newly analysed
     */
    public int catchUp(MarketEngine marketEngine) {
        long last = marketEngine.lastCandleOpenTime();
        if (last < 0) {
            return 0;
        }
        long step = marketEngine.key().timeframe().durationMillis();
        List<Candle> pending = candles.findRange(marketEngine.market().getId(),
                Instant.ofEpochMilli(last + step), Instant.now(), 10_000);

        int analysed = 0;
        for (Candle candle : pending) {
            EngineUpdate update = marketEngine.process(candle);
            if (!update.accepted()) {
                continue;
            }
            persist(marketEngine, update);
            for (TradingSignal signal : update.newSignals()) {
                notifications.dispatch(signal);
            }
            analysed++;
        }
        if (analysed > 0) {
            log.info("Caught up {} candle(s) for {} that the live stream had not delivered",
                    analysed, marketEngine.key());
        }
        return analysed;
    }

    /**
     * Retires stored levels the engine no longer follows, and aligns signals with their levels.
     *
     * <p>Run with the engine locked, so no candle can create a level between reading the engine's
     * active set and updating the rows. See {@link AnalysisResultStore#reconcileWithEngine}.
     */
    public AnalysisResultStore.Reconciliation reconcileStoredLevels(MarketEngine marketEngine) {
        AnalysisResultStore.Reconciliation outcome = marketEngine.withEngineLocked(engine ->
                store.reconcileWithEngine(marketEngine.market().getId(), engine.activeLevelIds()));
        if (outcome.changedAnything()) {
            log.info("Reconciled stored levels for {}: {} level(s) no longer tracked marked EXPIRED, "
                    + "{} signal(s) aligned with their level", marketEngine.key(),
                    outcome.levelsRetired(), outcome.signalsRetired());
        }
        return outcome;
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
                engineFactory.detectionStrategyName(), marketEngine.unsafeEngine().config().fingerprint());
        if (!update.newSignals().isEmpty()) {
            fillNextPotential(marketEngine);
        }
    }

    /**
     * Works out the next potential low / high of every signal of the market that has none yet.
     * Never throws: a failure only delays the value until the next reconciliation.
     */
    public int fillNextPotential(MarketEngine marketEngine) {
        try {
            int updated = nextPotential.fillMissing(marketEngine.market().getId(), marketEngine.key(),
                    marketEngine.unsafeEngine().config());
            if (updated > 1) {
                log.info("Worked out the next potential low/high of {} signal(s) for {}",
                        updated, marketEngine.key());
            }
            return updated;
        } catch (Exception ex) {
            log.warn("Could not work out next potential low/high for {}: {}",
                    marketEngine.key(), ex.toString());
            return 0;
        }
    }

    /**
     * Works out the next potential low / high of signals stored for markets that are no longer
     * watched, e.g. a timeframe taken off the watch list. No engine runs for them, so nothing else
     * would, and their candles are still stored.
     */
    public int fillNextPotentialOfUnwatchedMarkets() {
        int total = 0;
        for (MarketEntity market : markets.findAll()) {
            if (!market.getExchange().equals(exchangeProperties.getName())) {
                continue;
            }
            MarketKey key;
            try {
                key = market.toKey();
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (engines.containsKey(key)) {
                continue;
            }
            try {
                int updated = nextPotential.fillMissing(market.getId(), key, configFor(key));
                if (updated > 0) {
                    log.info("Worked out the next potential low/high of {} signal(s) for unwatched {}",
                            updated, key);
                }
                total += updated;
            } catch (Exception ex) {
                log.warn("Could not work out next potential low/high for unwatched {}: {}",
                        key, ex.toString());
            }
        }
        return total;
    }

    /**
     * Stops analysing a market: its engine is dropped, so live candles for it are ignored until it
     * is initialised again. Everything stored about it stays.
     */
    public void discard(MarketKey key) {
        engines.remove(key);
    }

    /**
     * Retires the stored levels of markets taken off the watch list. Nothing updates them any more,
     * so an ACTIVE row would show as a live level forever: every ACTIVE level becomes EXPIRED, and
     * its ACTIVE signals with it, exactly as for a level the engine stopped tracking (see
     * {@link #reconcileStoredLevels}). If the market is watched again, its replay brings back the
     * levels that are still active.
     *
     * <p>Only markets on a watched timeframe: data of a timeframe taken off the configuration
     * entirely is left as it is.
     *
     * @return how many levels were retired
     */
    public int retireUnwatchedMarkets(Set<String> watchedSymbols, Collection<Timeframe> watchedTimeframes) {
        int retired = 0;
        for (MarketEntity market : markets.findAll()) {
            if (!market.getExchange().equals(exchangeProperties.getName())
                    || watchedSymbols.contains(market.getSymbol())) {
                continue;
            }
            MarketKey key;
            try {
                key = market.toKey();
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (!watchedTimeframes.contains(key.timeframe()) || engines.containsKey(key)) {
                continue;
            }
            AnalysisResultStore.Reconciliation outcome = store.reconcileWithEngine(market.getId(), Set.of());
            if (outcome.changedAnything()) {
                log.info("{} is no longer watched: {} level(s) marked EXPIRED, {} signal(s) with them",
                        key, outcome.levelsRetired(), outcome.signalsRetired());
            }
            retired += outcome.levelsRetired();
        }
        return retired;
    }

    public Optional<MarketEngine> engine(MarketKey key) {
        return Optional.ofNullable(engines.get(key));
    }

    public Collection<MarketEngine> allEngines() {
        return engines.values();
    }
}
