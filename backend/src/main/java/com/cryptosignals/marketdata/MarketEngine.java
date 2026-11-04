package com.cryptosignals.marketdata;

import com.cryptosignals.analysis.engine.EngineUpdate;
import com.cryptosignals.analysis.engine.LevelAnalysisEngine;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.persistence.entity.MarketEntity;

import java.util.function.Function;

/**
 * One market's engine plus the state it needs, with access serialised.
 *
 * <p>The engine is a single-threaded state machine by design, but candles reach it from two places:
 * the websocket's callback threads and the scheduled reconciliation job. This holder is the one
 * place that guarantees they never interleave, so the engine keeps its ordering guarantee — and
 * with it determinism — without any locking inside the hot analysis path itself.
 */
public final class MarketEngine {

    private final MarketKey key;
    private final MarketEntity market;
    private final LevelAnalysisEngine engine;
    private final Object lock = new Object();

    public MarketEngine(MarketKey key, MarketEntity market, LevelAnalysisEngine engine) {
        this.key = key;
        this.market = market;
        this.engine = engine;
    }

    public MarketKey key() {
        return key;
    }

    public MarketEntity market() {
        return market;
    }

    /** Feeds one closed candle. Returns what changed, for the caller to persist and notify. */
    public EngineUpdate process(Candle candle) {
        synchronized (lock) {
            return engine.onClosedCandle(candle);
        }
    }

    public void switchToLive() {
        synchronized (lock) {
            engine.switchToLive();
        }
    }

    public boolean isHistorical() {
        synchronized (lock) {
            return engine.isHistorical();
        }
    }

    public long lastCandleOpenTime() {
        synchronized (lock) {
            return engine.lastCandleOpenTime();
        }
    }

    /** Runs an action with the engine held still: no candle is processed until it returns. */
    public <T> T withEngineLocked(Function<LevelAnalysisEngine, T> action) {
        synchronized (lock) {
            return action.apply(engine);
        }
    }

    public LevelAnalysisEngine unsafeEngine() {
        return engine;
    }
}
