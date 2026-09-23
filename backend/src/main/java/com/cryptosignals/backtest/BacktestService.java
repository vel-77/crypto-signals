package com.cryptosignals.backtest;

import com.cryptosignals.analysis.LevelAnalysisEngineFactory;
import com.cryptosignals.analysis.engine.LevelAnalysisEngine;
import com.cryptosignals.analysis.model.AnalysisResult;
import com.cryptosignals.config.ExchangeProperties;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.exchange.ExchangeClient;
import com.cryptosignals.persistence.AnalysisResultStore;
import com.cryptosignals.persistence.entity.MarketEntity;
import com.cryptosignals.persistence.jdbc.CandleJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Runs the detection engine over a historical range without touching live state.
 *
 * <p>Critically, this is the <em>same</em> engine, fed the same way, as the live path: a backtest
 * is not a reimplementation of the rules, so a result here is a genuine prediction of what the
 * running system would have done. Nothing is written to the levels, touches or signals tables, so a
 * backtest can be run against production data at any time without disturbing it.
 */
@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    private final CandleJdbcRepository candles;
    private final AnalysisResultStore store;
    private final ExchangeClient exchange;
    private final ExchangeProperties exchangeProperties;
    private final LevelAnalysisEngineFactory engineFactory;

    public BacktestService(CandleJdbcRepository candles, AnalysisResultStore store,
                           ExchangeClient exchange, ExchangeProperties exchangeProperties,
                           LevelAnalysisEngineFactory engineFactory) {
        this.candles = candles;
        this.store = store;
        this.exchange = exchange;
        this.exchangeProperties = exchangeProperties;
        this.engineFactory = engineFactory;
    }

    public BacktestResult run(BacktestRequest request) {
        MarketEntity market = store.resolveMarket(exchangeProperties.getName(), request.market());

        if (request.fetchMissing()) {
            fetchMissing(market, request);
        }
        return analyse(market, request);
    }

    private void fetchMissing(MarketEntity market, BacktestRequest request) {
        long have = candles.count(market.getId());
        List<Candle> fetched = exchange.fetchCandles(request.market().symbol(),
                request.market().timeframe(), request.from(), request.to(), 1_000_000);
        if (!fetched.isEmpty()) {
            candles.upsert(market.getId(), fetched);
        }
        log.info("Backtest data for {}: had {}, fetched {}", request.market(), have, fetched.size());
    }

    /**
     * Streams the range through a fresh engine.
     *
     * <p>Cursor-based: a multi-year one-minute backtest is millions of candles, and loading them
     * into a list first would be the only part of this system that does not scale. The read
     * transaction that the server-side cursor needs is opened by the repository itself, which is a
     * separate bean and therefore genuinely proxied — annotating this private method would look
     * like it did something and would not.
     */
    private BacktestResult analyse(MarketEntity market, BacktestRequest request) {
        long startedAt = System.currentTimeMillis();
        LevelAnalysisEngine engine = engineFactory.create(request.market(), request.config(), true);

        candles.stream(market.getId(), request.from(), request.to(), engine::onClosedCandle);

        AnalysisResult result = engine.result();
        long duration = System.currentTimeMillis() - startedAt;

        log.info("Backtest {} {}..{}: {} candles, {} levels, {} signals, {} breakouts in {} ms",
                request.market(), request.from(), request.to(), result.candlesProcessed(),
                result.levels().size(), result.signals().size(), result.breakouts().size(), duration);

        return new BacktestResult(
                request.market().symbol(), request.market().timeframe().code(),
                request.from(), request.to(), result.candlesProcessed(), duration,
                request.config().fingerprint(), engineFactory.detectionStrategyName(),
                result.levels(), result.signals(), result.breakouts());
    }
}
