package com.cryptosignals.backtest;

import com.cryptosignals.analysis.model.BreakoutEvent;
import com.cryptosignals.analysis.model.DetectedLevel;
import com.cryptosignals.analysis.model.TradingSignal;
import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.domain.LevelType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a backtest produced, with the headline counts pre-computed.
 *
 * <p>The point of a backtest here is not profit and loss — this system places no orders. It is to
 * let a human compare what the algorithm found against what they would have drawn on the same
 * chart, which is why the result keeps every level and every touch rather than just a summary.
 */
public record BacktestResult(
        String symbol,
        String timeframe,
        Instant from,
        Instant to,
        int candlesAnalysed,
        long durationMillis,
        String configFingerprint,
        String strategy,
        List<DetectedLevel> levels,
        List<TradingSignal> signals,
        List<BreakoutEvent> breakouts) {

    public BacktestResult {
        levels = levels == null ? List.of() : List.copyOf(levels);
        signals = signals == null ? List.of() : List.copyOf(signals);
        breakouts = breakouts == null ? List.of() : List.copyOf(breakouts);
    }

    /**
     * How a single signal fared afterwards.
     *
     * @param candlesHeld candles between the signal and the breakout that ended its level; if the
     *                    level never broke, candles between the signal and the end of the test
     * @param broken      false when the level was still standing when the test window ran out, in
     *                    which case {@code candlesHeld} is a lower bound, not an observation
     */
    public record SignalOutcome(TradingSignal signal, int candlesHeld, boolean broken) {
    }

    /** Pairs every signal with the fate of the level that produced it. */
    public List<SignalOutcome> outcomes() {
        Map<String, DetectedLevel> byId = new HashMap<>();
        for (DetectedLevel level : levels) {
            byId.putIfAbsent(level.id(), level);
        }
        List<SignalOutcome> out = new ArrayList<>(signals.size());
        for (TradingSignal signal : signals) {
            DetectedLevel level = byId.get(signal.levelId());
            BreakoutEvent breakout = level == null ? null : level.breakout();
            int held = breakout != null
                    ? Math.max(0, breakout.confirmedAtIndex() - signal.detectedAtIndex())
                    : Math.max(0, candlesAnalysed - 1 - signal.detectedAtIndex());
            out.add(new SignalOutcome(signal, held, breakout != null));
        }
        return out;
    }

    public Summary summary(int minTouches) {
        List<SignalOutcome> outcomes = outcomes();
        int[] held = outcomes.stream().mapToInt(SignalOutcome::candlesHeld).sorted().toArray();

        return new Summary(
                levels.size(),
                (int) levels.stream().filter(l -> l.touchCount() >= minTouches).count(),
                signals.size(),
                (int) signals.stream().filter(s -> s.type() == LevelType.RESISTANCE).count(),
                (int) signals.stream().filter(s -> s.type() == LevelType.SUPPORT).count(),
                (int) outcomes.stream().filter(SignalOutcome::broken).count(),
                (int) outcomes.stream().filter(o -> !o.broken()).count(),
                percentile(held, 0.25),
                percentile(held, 0.50),
                percentile(held, 0.75),
                (int) levels.stream().filter(l -> l.status() == LevelStatus.BROKEN).count(),
                (int) levels.stream().filter(l -> l.status() == LevelStatus.ACTIVE).count(),
                breakouts.size(),
                candlesAnalysed);
    }

    private static int percentile(int[] sorted, double fraction) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.floor(fraction * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    /**
     * Headline counts.
     *
     * <p>Note what {@code signalsWhoseLevelLaterBroke} does and does not mean. A support level that
     * is respected for two days and then gives way has done its job; it is not a false signal. Over
     * a long window almost every level eventually breaks, so the count on its own says more about
     * the window length than about the algorithm. The percentile fields are the honest measure:
     * they say how long a level typically kept holding after it signalled.
     *
     * @param levelsDiscovered            every level the strategy ever created
     * @param qualifiedLevels             levels that reached the configured touch threshold
     * @param signalsStillUnbroken        levels still standing when the window ended; their hold
     *                                    times are censored lower bounds
     * @param medianCandlesHeldAfterSignal typical candles a level kept holding after signalling
     */
    public record Summary(
            int levelsDiscovered,
            int qualifiedLevels,
            int signals,
            int resistanceSignals,
            int supportSignals,
            int signalsWhoseLevelLaterBroke,
            int signalsStillUnbroken,
            int p25CandlesHeldAfterSignal,
            int medianCandlesHeldAfterSignal,
            int p75CandlesHeldAfterSignal,
            int brokenLevels,
            int activeLevels,
            int breakoutEvents,
            int candlesAnalysed) {

        /** Share of signals whose level had not broken by the end of the window, as a 0..1 fraction. */
        public double stillUnbrokenRatio() {
            return signals == 0 ? 0.0 : (double) signalsStillUnbroken / signals;
        }
    }
}
