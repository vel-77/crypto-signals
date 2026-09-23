package com.cryptosignals.analysis.strategy;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.engine.LevelState;
import com.cryptosignals.analysis.model.BreakoutEvent;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.LevelType;

/**
 * Default breakout rule (specification section 8).
 *
 * <p>A single wick poking past the level is explicitly <em>not</em> a breakout unless configured to
 * be. Price must clear a threshold set beyond the touch zone, and it must do so for
 * {@code confirmationCandles} consecutive candles:
 *
 * <pre>
 *   resistance: threshold = price * (1 + breakoutTolerancePercent / 100)
 *   support:    threshold = price * (1 - breakoutTolerancePercent / 100)
 * </pre>
 *
 * <p>Which part of the candle must clear the threshold is configurable and additive:
 * {@code closeBeyondLevel} tests the close, {@code wickBreakAllowed} tests the wick, and enabling
 * both means either qualifies. The configuration object rejects disabling both, and rejects a
 * breakout tolerance tighter than the touch tolerance, since either would make signals impossible.
 *
 * <p>The run counter resets on the first candle that fails to qualify, so
 * {@code confirmationCandles} genuinely means "consecutive".
 */
public final class ConfirmedBreakoutDetector implements BreakoutDetector {

    @Override
    public String name() {
        return "confirmed-close";
    }

    @Override
    public BreakoutEvent detect(LevelState level, Candle candle, int index, AnalysisConfig config) {
        double threshold = threshold(level, config);
        boolean resistance = level.type() == LevelType.RESISTANCE;

        boolean wickBeyond = resistance ? candle.high() > threshold : candle.low() < threshold;
        boolean closeBeyond = resistance ? candle.close() > threshold : candle.close() < threshold;
        boolean qualifies = (config.wickBreakAllowed() && wickBeyond)
                || (config.closeBeyondLevel() && closeBeyond);

        if (!qualifies) {
            level.resetBreachRun();
            return null;
        }

        level.registerBreach(index);
        if (level.breachRun() < config.confirmationCandles()) {
            return null;
        }

        return new BreakoutEvent(level.id(), level.type(), level.price(), threshold,
                level.firstBreachIndex(), index, candle.openTime(), candle.close(),
                config.confirmationCandles(), level.touchCount());
    }

    /** Price that must be cleared for the level to be considered broken. */
    public static double threshold(LevelState level, AnalysisConfig config) {
        double offset = level.price() * config.breakoutTolerancePercent() / 100.0;
        return level.type() == LevelType.RESISTANCE
                ? level.price() + offset
                : level.price() - offset;
    }
}
