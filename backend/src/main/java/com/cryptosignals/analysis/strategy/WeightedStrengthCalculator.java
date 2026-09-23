package com.cryptosignals.analysis.strategy;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.engine.LevelState;
import com.cryptosignals.analysis.model.Touch;

/**
 * Default strength score: a weighted blend of three scale-free components, each normalised to 0..1
 * and then combined using the configured weights.
 *
 * <ul>
 *   <li><b>Touch count</b> — saturates at {@code minTouches + 2} touches. More tests mean a more
 *       respected level.</li>
 *   <li><b>Rejection quality</b> — the mean distance price travelled away from the level after its
 *       touches, normalised against three zone half-widths. A level that price merely grazes scores
 *       lower than one price is thrown away from.</li>
 *   <li><b>Time span</b> — candles between creation and the most recent touch, saturating at
 *       {@link #SPAN_SATURATION_CANDLES}. A level respected across months beats one respected
 *       across an hour.</li>
 * </ul>
 *
 * <p>The saturation constants are properties of this scoring strategy, not of the engine; a
 * different scoring philosophy is a different implementation of {@link StrengthCalculator}.
 */
public final class WeightedStrengthCalculator implements StrengthCalculator {

    /** Time-span component reaches its maximum at this many candles. */
    public static final int SPAN_SATURATION_CANDLES = 500;

    /** Rejection component reaches its maximum at this multiple of the zone half-width. */
    public static final double REJECTION_SATURATION_ZONES = 3.0;

    @Override
    public String name() {
        return "weighted";
    }

    @Override
    public double strength(LevelState level, AnalysisConfig config) {
        double touchScore = clamp01((double) level.touchCount() / (config.minTouches() + 2));

        double rejectionSaturation = config.touchTolerancePercent() * REJECTION_SATURATION_ZONES;
        double meanRejection = 0;
        if (!level.touches().isEmpty()) {
            double sum = 0;
            for (Touch touch : level.touches()) {
                sum += touch.rejectionPercent();
            }
            meanRejection = sum / level.touches().size();
        }
        double rejectionScore = rejectionSaturation > 0
                ? clamp01(meanRejection / rejectionSaturation)
                : 0.0;

        int span = Math.max(0, level.lastTouchIndex() - level.createdAtIndex());
        double spanScore = clamp01((double) span / SPAN_SATURATION_CANDLES);

        double weightSum = config.strengthTouchWeight()
                + config.strengthRejectionWeight()
                + config.strengthSpanWeight();

        double weighted = config.strengthTouchWeight() * touchScore
                + config.strengthRejectionWeight() * rejectionScore
                + config.strengthSpanWeight() * spanScore;

        return clamp01(weighted / weightSum);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
