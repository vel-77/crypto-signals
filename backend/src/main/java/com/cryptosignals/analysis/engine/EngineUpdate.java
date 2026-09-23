package com.cryptosignals.analysis.engine;

import com.cryptosignals.analysis.model.BreakoutEvent;
import com.cryptosignals.analysis.model.DetectedLevel;
import com.cryptosignals.analysis.model.TradingSignal;

import java.util.List;

/**
 * Everything one candle changed. Returned from every call so the live path can persist and notify
 * incrementally instead of rescanning engine state.
 *
 * @param accepted            false when the candle was ignored as a duplicate or out-of-order
 * @param index               absolute index assigned to the candle
 * @param candleTime          open time of the candle, epoch millis
 * @param newSignals          signals emitted on this candle
 * @param newBreakouts        breakouts confirmed on this candle
 * @param invalidatedSignals  previously active signals invalidated by those breakouts
 * @param changedLevels       snapshots of every level whose state changed on this candle
 */
public record EngineUpdate(
        boolean accepted,
        int index,
        long candleTime,
        List<TradingSignal> newSignals,
        List<BreakoutEvent> newBreakouts,
        List<TradingSignal> invalidatedSignals,
        List<DetectedLevel> changedLevels) {

    public EngineUpdate {
        newSignals = newSignals == null ? List.of() : List.copyOf(newSignals);
        newBreakouts = newBreakouts == null ? List.of() : List.copyOf(newBreakouts);
        invalidatedSignals = invalidatedSignals == null ? List.of() : List.copyOf(invalidatedSignals);
        changedLevels = changedLevels == null ? List.of() : List.copyOf(changedLevels);
    }

    public static EngineUpdate ignored(long candleTime) {
        return new EngineUpdate(false, -1, candleTime, List.of(), List.of(), List.of(), List.of());
    }

    public boolean hasChanges() {
        return !newSignals.isEmpty() || !newBreakouts.isEmpty()
                || !invalidatedSignals.isEmpty() || !changedLevels.isEmpty();
    }
}
