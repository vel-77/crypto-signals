package com.cryptosignals.analysis;

import com.cryptosignals.domain.PriceAggregation;
import com.cryptosignals.domain.SignalTrigger;
import com.cryptosignals.domain.ToleranceMode;
import com.cryptosignals.domain.TouchMode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.CRC32;

/**
 * Every tunable rule of the detection engine. Nothing in the engine is hardcoded; this object is
 * the single source of truth and is deliberately a plain immutable value type with no Spring or
 * persistence coupling, so the engine can be exercised from a unit test with one line.
 *
 * <p>The defaults encode the documented v1 interpretation of the business rule described in
 * {@code docs/ALGORITHM.md}. Deviating from them is a configuration change, not a code change.
 *
 * <h2>Level discovery</h2>
 * @param swingLeftBars            candles to the left that a swing point must dominate
 * @param swingRightBars           candles to the right that a swing point must dominate; also the
 *                                 confirmation latency, i.e. a swing is only <em>known</em> this
 *                                 many candles after it printed
 * @param levelPriceAggregation    how clustered swing prices collapse into one level price
 * @param clusterTolerancePercent  max distance (percent of level price) for a new swing to join an
 *                                 existing level rather than create a new one
 *
 * <h2>Zone width (the "same price zone" tolerance)</h2>
 * @param toleranceMode            percentage, volatility (ATR) based, or the max of both
 * @param touchTolerancePercent    zone half-width as a percent of the level price
 * @param atrToleranceMultiplier   zone half-width as a multiple of ATR
 * @param atrPeriod                Wilder ATR lookback
 *
 * <h2>Touch separation (what makes two touches "separate")</h2>
 * @param touchMode                which part of a candle must reach the zone
 * @param minCandlesBetweenTouches candles that must be spent fully outside the zone between touches
 * @param minDeparturePercent      how far away from the level price must travel between touches,
 *                                 as a percent of the level price
 * @param minTimeBetweenTouches    optional wall-clock separation; {@link Duration#ZERO} disables it
 *
 * <h2>Breakout</h2>
 * @param breakoutTolerancePercent how far beyond the level price must go to count as breaking it
 * @param confirmationCandles      consecutive qualifying candles required to confirm the breakout
 * @param closeBeyondLevel         require the candle close (not just the wick) beyond the threshold
 * @param wickBreakAllowed         allow a wick alone to break the level
 *
 * <h2>Signal</h2>
 * @param minTouches                     touches required to emit (the "3" in 3-touch)
 * @param signalTrigger                  emit on the Nth touch or only after a confirmed rejection
 * @param minLevelStrength               minimum strength score (0..1) for a level to emit
 * @param emitSignalOnEveryTouchAfterMin emit again on touch 4, 5, ... instead of only once
 *
 * <h2>Level lifecycle</h2>
 * @param maxActiveLevelsPerMarket bound on retained levels per market (memory safety)
 * @param levelExpiryCandles       drop untouched levels after this many candles; 0 disables
 * @param enableRoleFlip           on a confirmed breakout, immediately seed an opposite-type level
 * @param supersedeEnabled         master switch for both supersede rules below
 * @param supersedeNearbyLevels    proximity rule: among established levels of one role that sit
 *                                 within {@code supersedeMaxDistancePercent} of their neighbour,
 *                                 keep only the outermost (highest resistance, lowest support)
 * @param supersedeOnPricePass     price rule: retire a level once price has moved past it and a
 *                                 stronger level of the same role, close by on the far side, is
 *                                 nearer to price
 * @param supersedeMaxDistancePercent the gap (percent of the lower / passed level's price) within
 *                                 which two levels count as the same price area, for both rules
 *
 * <h2>Strength scoring weights</h2>
 * @param strengthTouchWeight     weight of the touch count component
 * @param strengthRejectionWeight weight of the average rejection-quality component
 * @param strengthSpanWeight      weight of the time-span component
 */
public record AnalysisConfig(
        // level discovery
        int swingLeftBars,
        int swingRightBars,
        PriceAggregation levelPriceAggregation,
        double clusterTolerancePercent,
        // zone width
        ToleranceMode toleranceMode,
        double touchTolerancePercent,
        double atrToleranceMultiplier,
        int atrPeriod,
        // touch separation
        TouchMode touchMode,
        int minCandlesBetweenTouches,
        double minDeparturePercent,
        Duration minTimeBetweenTouches,
        // breakout
        double breakoutTolerancePercent,
        int confirmationCandles,
        boolean closeBeyondLevel,
        boolean wickBreakAllowed,
        // signal
        int minTouches,
        SignalTrigger signalTrigger,
        double minLevelStrength,
        boolean emitSignalOnEveryTouchAfterMin,
        // lifecycle
        int maxActiveLevelsPerMarket,
        int levelExpiryCandles,
        boolean enableRoleFlip,
        // relevance
        boolean supersedeEnabled,
        boolean supersedeNearbyLevels,
        boolean supersedeOnPricePass,
        double supersedeMaxDistancePercent,
        // strength weights
        double strengthTouchWeight,
        double strengthRejectionWeight,
        double strengthSpanWeight) {

    public AnalysisConfig {
        positive(swingLeftBars, "swingLeftBars");
        positive(swingRightBars, "swingRightBars");
        positive(atrPeriod, "atrPeriod");
        positive(minTouches, "minTouches");
        positive(confirmationCandles, "confirmationCandles");
        positive(maxActiveLevelsPerMarket, "maxActiveLevelsPerMarket");
        nonNegative(minCandlesBetweenTouches, "minCandlesBetweenTouches");
        nonNegative(levelExpiryCandles, "levelExpiryCandles");
        positiveDouble(touchTolerancePercent, "touchTolerancePercent");
        positiveDouble(clusterTolerancePercent, "clusterTolerancePercent");
        positiveDouble(breakoutTolerancePercent, "breakoutTolerancePercent");
        nonNegativeDouble(atrToleranceMultiplier, "atrToleranceMultiplier");
        nonNegativeDouble(minDeparturePercent, "minDeparturePercent");
        nonNegativeDouble(minLevelStrength, "minLevelStrength");
        nonNegativeDouble(supersedeMaxDistancePercent, "supersedeMaxDistancePercent");
        nonNegativeDouble(strengthTouchWeight, "strengthTouchWeight");
        nonNegativeDouble(strengthRejectionWeight, "strengthRejectionWeight");
        nonNegativeDouble(strengthSpanWeight, "strengthSpanWeight");

        if (minTouches < 2) {
            throw new IllegalArgumentException("minTouches must be >= 2, got " + minTouches);
        }
        if (minLevelStrength > 1.0) {
            throw new IllegalArgumentException("minLevelStrength is a 0..1 score, got " + minLevelStrength);
        }
        // A breakout that triggers inside the touch zone would make every touch a breakout and no
        // signal could ever form. This is a configuration contradiction, so fail fast and loudly.
        if (breakoutTolerancePercent < touchTolerancePercent) {
            throw new IllegalArgumentException(
                    "breakoutTolerancePercent (" + breakoutTolerancePercent
                            + ") must be >= touchTolerancePercent (" + touchTolerancePercent
                            + "), otherwise every touch would instantly count as a breakout");
        }
        if (toleranceMode == null || touchMode == null || signalTrigger == null
                || levelPriceAggregation == null) {
            throw new IllegalArgumentException("enum configuration values must not be null");
        }
        if (minTimeBetweenTouches == null) {
            minTimeBetweenTouches = Duration.ZERO;
        }
        if (minTimeBetweenTouches.isNegative()) {
            throw new IllegalArgumentException("minTimeBetweenTouches must not be negative");
        }
        if (!closeBeyondLevel && !wickBreakAllowed) {
            throw new IllegalArgumentException(
                    "closeBeyondLevel=false with wickBreakAllowed=false means a level can never "
                            + "break; enable at least one breakout condition");
        }
        double weightSum = strengthTouchWeight + strengthRejectionWeight + strengthSpanWeight;
        if (weightSum <= 0) {
            throw new IllegalArgumentException("at least one strength weight must be > 0");
        }
    }

    /** The documented v1 defaults. */
    public static AnalysisConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Stable fingerprint of the configuration. Persisted alongside results so that a stored
     * analysis can always be traced back to the exact rules that produced it, and so a config
     * change can invalidate stale derived data.
     */
    public String fingerprint() {
        // Not hashCode(): a record's hashCode folds in each enum's identity hash, which changes on
        // every JVM start, so the same rules would get a new fingerprint after each restart. The
        // component-wise toString() is fully determined by the values.
        CRC32 crc = new CRC32();
        crc.update(toString().getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", crc.getValue());
    }

    private static void positive(int v, String name) {
        if (v <= 0) {
            throw new IllegalArgumentException(name + " must be > 0, got " + v);
        }
    }

    private static void nonNegative(int v, String name) {
        if (v < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + v);
        }
    }

    private static void positiveDouble(double v, String name) {
        if (!Double.isFinite(v) || v <= 0) {
            throw new IllegalArgumentException(name + " must be finite and > 0, got " + v);
        }
    }

    private static void nonNegativeDouble(double v, String name) {
        if (!Double.isFinite(v) || v < 0) {
            throw new IllegalArgumentException(name + " must be finite and >= 0, got " + v);
        }
    }

    /** Mutable builder; every field carries the documented default. */
    public static final class Builder {
        private int swingLeftBars = 3;
        private int swingRightBars = 3;
        private PriceAggregation levelPriceAggregation = PriceAggregation.MEAN;
        private double clusterTolerancePercent = 0.15;

        private ToleranceMode toleranceMode = ToleranceMode.PERCENT;
        private double touchTolerancePercent = 0.1;
        private double atrToleranceMultiplier = 0.25;
        private int atrPeriod = 14;

        private TouchMode touchMode = TouchMode.WICK;
        private int minCandlesBetweenTouches = 10;
        private double minDeparturePercent = 0.3;
        private Duration minTimeBetweenTouches = Duration.ZERO;

        private double breakoutTolerancePercent = 0.2;
        private int confirmationCandles = 1;
        private boolean closeBeyondLevel = true;
        private boolean wickBreakAllowed = false;

        private int minTouches = 3;
        private SignalTrigger signalTrigger = SignalTrigger.ON_TOUCH;
        private double minLevelStrength = 0.0;
        private boolean emitSignalOnEveryTouchAfterMin = false;

        private int maxActiveLevelsPerMarket = 250;
        private int levelExpiryCandles = 0;
        private boolean enableRoleFlip = false;

        private boolean supersedeEnabled = true;
        private boolean supersedeNearbyLevels = true;
        private boolean supersedeOnPricePass = true;
        private double supersedeMaxDistancePercent = 0.5;

        private double strengthTouchWeight = 0.5;
        private double strengthRejectionWeight = 0.3;
        private double strengthSpanWeight = 0.2;

        private Builder() {
        }

        private Builder(AnalysisConfig c) {
            this.swingLeftBars = c.swingLeftBars;
            this.swingRightBars = c.swingRightBars;
            this.levelPriceAggregation = c.levelPriceAggregation;
            this.clusterTolerancePercent = c.clusterTolerancePercent;
            this.toleranceMode = c.toleranceMode;
            this.touchTolerancePercent = c.touchTolerancePercent;
            this.atrToleranceMultiplier = c.atrToleranceMultiplier;
            this.atrPeriod = c.atrPeriod;
            this.touchMode = c.touchMode;
            this.minCandlesBetweenTouches = c.minCandlesBetweenTouches;
            this.minDeparturePercent = c.minDeparturePercent;
            this.minTimeBetweenTouches = c.minTimeBetweenTouches;
            this.breakoutTolerancePercent = c.breakoutTolerancePercent;
            this.confirmationCandles = c.confirmationCandles;
            this.closeBeyondLevel = c.closeBeyondLevel;
            this.wickBreakAllowed = c.wickBreakAllowed;
            this.minTouches = c.minTouches;
            this.signalTrigger = c.signalTrigger;
            this.minLevelStrength = c.minLevelStrength;
            this.emitSignalOnEveryTouchAfterMin = c.emitSignalOnEveryTouchAfterMin;
            this.maxActiveLevelsPerMarket = c.maxActiveLevelsPerMarket;
            this.levelExpiryCandles = c.levelExpiryCandles;
            this.enableRoleFlip = c.enableRoleFlip;
            this.supersedeEnabled = c.supersedeEnabled;
            this.supersedeNearbyLevels = c.supersedeNearbyLevels;
            this.supersedeOnPricePass = c.supersedeOnPricePass;
            this.supersedeMaxDistancePercent = c.supersedeMaxDistancePercent;
            this.strengthTouchWeight = c.strengthTouchWeight;
            this.strengthRejectionWeight = c.strengthRejectionWeight;
            this.strengthSpanWeight = c.strengthSpanWeight;
        }

        public Builder swingLeftBars(int v) { this.swingLeftBars = v; return this; }
        public Builder swingRightBars(int v) { this.swingRightBars = v; return this; }
        public Builder swingBars(int v) { this.swingLeftBars = v; this.swingRightBars = v; return this; }
        public Builder levelPriceAggregation(PriceAggregation v) { this.levelPriceAggregation = v; return this; }
        public Builder clusterTolerancePercent(double v) { this.clusterTolerancePercent = v; return this; }
        public Builder toleranceMode(ToleranceMode v) { this.toleranceMode = v; return this; }
        public Builder touchTolerancePercent(double v) { this.touchTolerancePercent = v; return this; }
        public Builder atrToleranceMultiplier(double v) { this.atrToleranceMultiplier = v; return this; }
        public Builder atrPeriod(int v) { this.atrPeriod = v; return this; }
        public Builder touchMode(TouchMode v) { this.touchMode = v; return this; }
        public Builder minCandlesBetweenTouches(int v) { this.minCandlesBetweenTouches = v; return this; }
        public Builder minDeparturePercent(double v) { this.minDeparturePercent = v; return this; }
        public Builder minTimeBetweenTouches(Duration v) { this.minTimeBetweenTouches = v; return this; }
        public Builder breakoutTolerancePercent(double v) { this.breakoutTolerancePercent = v; return this; }
        public Builder confirmationCandles(int v) { this.confirmationCandles = v; return this; }
        public Builder closeBeyondLevel(boolean v) { this.closeBeyondLevel = v; return this; }
        public Builder wickBreakAllowed(boolean v) { this.wickBreakAllowed = v; return this; }
        public Builder minTouches(int v) { this.minTouches = v; return this; }
        public Builder signalTrigger(SignalTrigger v) { this.signalTrigger = v; return this; }
        public Builder minLevelStrength(double v) { this.minLevelStrength = v; return this; }
        public Builder emitSignalOnEveryTouchAfterMin(boolean v) { this.emitSignalOnEveryTouchAfterMin = v; return this; }
        public Builder maxActiveLevelsPerMarket(int v) { this.maxActiveLevelsPerMarket = v; return this; }
        public Builder levelExpiryCandles(int v) { this.levelExpiryCandles = v; return this; }
        public Builder enableRoleFlip(boolean v) { this.enableRoleFlip = v; return this; }
        public Builder supersedeEnabled(boolean v) { this.supersedeEnabled = v; return this; }
        public Builder supersedeNearbyLevels(boolean v) { this.supersedeNearbyLevels = v; return this; }
        public Builder supersedeOnPricePass(boolean v) { this.supersedeOnPricePass = v; return this; }
        public Builder supersedeMaxDistancePercent(double v) { this.supersedeMaxDistancePercent = v; return this; }
        public Builder strengthTouchWeight(double v) { this.strengthTouchWeight = v; return this; }
        public Builder strengthRejectionWeight(double v) { this.strengthRejectionWeight = v; return this; }
        public Builder strengthSpanWeight(double v) { this.strengthSpanWeight = v; return this; }

        public AnalysisConfig build() {
            return new AnalysisConfig(
                    swingLeftBars, swingRightBars, levelPriceAggregation, clusterTolerancePercent,
                    toleranceMode, touchTolerancePercent, atrToleranceMultiplier, atrPeriod,
                    touchMode, minCandlesBetweenTouches, minDeparturePercent, minTimeBetweenTouches,
                    breakoutTolerancePercent, confirmationCandles, closeBeyondLevel, wickBreakAllowed,
                    minTouches, signalTrigger, minLevelStrength, emitSignalOnEveryTouchAfterMin,
                    maxActiveLevelsPerMarket, levelExpiryCandles, enableRoleFlip,
                    supersedeEnabled, supersedeNearbyLevels, supersedeOnPricePass,
                    supersedeMaxDistancePercent,
                    strengthTouchWeight, strengthRejectionWeight, strengthSpanWeight);
        }
    }
}
