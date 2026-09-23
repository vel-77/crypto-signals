package com.cryptosignals.analysis.model;

import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.domain.MarketKey;

import java.util.List;

/**
 * Outcome of running the engine over a candle series. Used directly by the backtester and by the
 * startup historical replay.
 */
public record AnalysisResult(
        MarketKey market,
        List<DetectedLevel> levels,
        List<TradingSignal> signals,
        List<BreakoutEvent> breakouts,
        int candlesProcessed,
        long firstCandleTime,
        long lastCandleTime,
        String configFingerprint) {

    public AnalysisResult {
        levels = levels == null ? List.of() : List.copyOf(levels);
        signals = signals == null ? List.of() : List.copyOf(signals);
        breakouts = breakouts == null ? List.of() : List.copyOf(breakouts);
    }

    public long activeLevelCount() {
        return levels.stream().filter(l -> l.status() == LevelStatus.ACTIVE).count();
    }

    public long brokenLevelCount() {
        return levels.stream().filter(l -> l.status() == LevelStatus.BROKEN).count();
    }

    /** Levels that reached the configured touch threshold, whether or not they later broke. */
    public long qualifiedLevelCount(int minTouches) {
        return levels.stream().filter(l -> l.touchCount() >= minTouches).count();
    }
}
