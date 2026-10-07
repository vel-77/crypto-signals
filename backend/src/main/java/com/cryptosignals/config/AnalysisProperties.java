package com.cryptosignals.config;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.domain.PriceAggregation;
import com.cryptosignals.domain.SignalTrigger;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.domain.ToleranceMode;
import com.cryptosignals.domain.TouchMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * External binding for every detection rule. Mirrors {@link AnalysisConfig} one-to-one so that no
 * threshold is ever hardcoded (specification section 14).
 *
 * <p>Bound from {@code crypto-signals.analysis.*}. Spring's relaxed binding accepts both
 * {@code min-touches} and {@code minTouches}.
 */
@ConfigurationProperties(prefix = "crypto-signals.analysis")
public class AnalysisProperties {

    // --- level discovery
    @Positive private int swingLeftBars = 3;
    @Positive private int swingRightBars = 3;
    private PriceAggregation levelPriceAggregation = PriceAggregation.MEAN;
    @Positive private double clusterTolerancePercent = 0.15;

    // --- zone width
    private ToleranceMode toleranceMode = ToleranceMode.PERCENT;
    @Positive private double touchTolerancePercent = 0.1;
    @PositiveOrZero private double atrToleranceMultiplier = 0.25;
    @Positive private int atrPeriod = 14;

    // --- touch separation
    private TouchMode touchMode = TouchMode.WICK;
    @PositiveOrZero private int minCandlesBetweenTouches = 10;
    @PositiveOrZero private double minDeparturePercent = 0.3;
    private Duration minTimeBetweenTouches = Duration.ZERO;

    // --- breakout
    @Positive private double breakoutTolerancePercent = 0.2;
    @Positive private int confirmationCandles = 1;
    private boolean closeBeyondLevel = true;
    private boolean wickBreakAllowed = false;

    // --- signal
    @Min(2) private int minTouches = 3;
    private SignalTrigger signalTrigger = SignalTrigger.ON_TOUCH;
    @PositiveOrZero private double minLevelStrength = 0.0;
    private boolean emitSignalOnEveryTouchAfterMin = false;

    // --- lifecycle
    @Positive private int maxActiveLevelsPerMarket = 250;
    @PositiveOrZero private int levelExpiryCandles = 0;
    private boolean enableRoleFlip = false;

    // --- relevance ("nearest level" rule)
    private boolean supersedeEnabled = true;
    /**
     * Maximum gap between a passed level and the level that supersedes it, per timeframe, as
     * {@code timeframe:percent} pairs. Wider timeframes move further per candle, so they allow a
     * wider gap. Timeframes not listed use {@link #supersedeDefaultMaxDistancePercent}.
     */
    private String supersedeMaxDistancePercent = "15m:0.5,30m:0.6,1h:0.75,4h:1.5";
    @PositiveOrZero private double supersedeDefaultMaxDistancePercent = 0.5;

    // --- strength weights
    @PositiveOrZero private double strengthTouchWeight = 0.5;
    @PositiveOrZero private double strengthRejectionWeight = 0.3;
    @PositiveOrZero private double strengthSpanWeight = 0.2;

    /** Engine configuration for one timeframe — the supersede distance depends on it. */
    public AnalysisConfig toAnalysisConfig(Timeframe timeframe) {
        return builder()
                .supersedeMaxDistancePercent(supersedeDistanceFor(timeframe))
                .build();
    }

    /** Engine configuration using the default supersede distance. */
    public AnalysisConfig toAnalysisConfig() {
        return builder().build();
    }

    /** The supersede distance configured for a timeframe, or the default if it is not listed. */
    public double supersedeDistanceFor(Timeframe timeframe) {
        Double configured = getSupersedeMaxDistanceByTimeframe().get(timeframe.code());
        return configured != null ? configured : supersedeDefaultMaxDistancePercent;
    }

    /** Parsed form of {@link #supersedeMaxDistancePercent}, also shown on the config endpoint. */
    public Map<String, Double> getSupersedeMaxDistanceByTimeframe() {
        Map<String, Double> parsed = new LinkedHashMap<>();
        if (supersedeMaxDistancePercent == null || supersedeMaxDistancePercent.isBlank()) {
            return parsed;
        }
        for (String pair : supersedeMaxDistancePercent.split(",")) {
            String[] parts = pair.trim().split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("supersede-max-distance-percent: expected "
                        + "'timeframe:percent' pairs such as '15m:0.5,1h:0.75', got '" + pair.trim() + "'");
            }
            parsed.put(Timeframe.fromCode(parts[0].trim()).code(), Double.parseDouble(parts[1].trim()));
        }
        return parsed;
    }

    /** Builds the immutable engine configuration, applying its stricter cross-field validation. */
    private AnalysisConfig.Builder builder() {
        return AnalysisConfig.builder()
                .swingLeftBars(swingLeftBars)
                .swingRightBars(swingRightBars)
                .levelPriceAggregation(levelPriceAggregation)
                .clusterTolerancePercent(clusterTolerancePercent)
                .toleranceMode(toleranceMode)
                .touchTolerancePercent(touchTolerancePercent)
                .atrToleranceMultiplier(atrToleranceMultiplier)
                .atrPeriod(atrPeriod)
                .touchMode(touchMode)
                .minCandlesBetweenTouches(minCandlesBetweenTouches)
                .minDeparturePercent(minDeparturePercent)
                .minTimeBetweenTouches(minTimeBetweenTouches)
                .breakoutTolerancePercent(breakoutTolerancePercent)
                .confirmationCandles(confirmationCandles)
                .closeBeyondLevel(closeBeyondLevel)
                .wickBreakAllowed(wickBreakAllowed)
                .minTouches(minTouches)
                .signalTrigger(signalTrigger)
                .minLevelStrength(minLevelStrength)
                .emitSignalOnEveryTouchAfterMin(emitSignalOnEveryTouchAfterMin)
                .maxActiveLevelsPerMarket(maxActiveLevelsPerMarket)
                .levelExpiryCandles(levelExpiryCandles)
                .enableRoleFlip(enableRoleFlip)
                .supersedeEnabled(supersedeEnabled)
                .supersedeMaxDistancePercent(supersedeDefaultMaxDistancePercent)
                .strengthTouchWeight(strengthTouchWeight)
                .strengthRejectionWeight(strengthRejectionWeight)
                .strengthSpanWeight(strengthSpanWeight);
    }

    public int getSwingLeftBars() { return swingLeftBars; }
    public void setSwingLeftBars(int v) { this.swingLeftBars = v; }
    public int getSwingRightBars() { return swingRightBars; }
    public void setSwingRightBars(int v) { this.swingRightBars = v; }
    public PriceAggregation getLevelPriceAggregation() { return levelPriceAggregation; }
    public void setLevelPriceAggregation(PriceAggregation v) { this.levelPriceAggregation = v; }
    public double getClusterTolerancePercent() { return clusterTolerancePercent; }
    public void setClusterTolerancePercent(double v) { this.clusterTolerancePercent = v; }
    public ToleranceMode getToleranceMode() { return toleranceMode; }
    public void setToleranceMode(ToleranceMode v) { this.toleranceMode = v; }
    public double getTouchTolerancePercent() { return touchTolerancePercent; }
    public void setTouchTolerancePercent(double v) { this.touchTolerancePercent = v; }
    public double getAtrToleranceMultiplier() { return atrToleranceMultiplier; }
    public void setAtrToleranceMultiplier(double v) { this.atrToleranceMultiplier = v; }
    public int getAtrPeriod() { return atrPeriod; }
    public void setAtrPeriod(int v) { this.atrPeriod = v; }
    public TouchMode getTouchMode() { return touchMode; }
    public void setTouchMode(TouchMode v) { this.touchMode = v; }
    public int getMinCandlesBetweenTouches() { return minCandlesBetweenTouches; }
    public void setMinCandlesBetweenTouches(int v) { this.minCandlesBetweenTouches = v; }
    public double getMinDeparturePercent() { return minDeparturePercent; }
    public void setMinDeparturePercent(double v) { this.minDeparturePercent = v; }
    public Duration getMinTimeBetweenTouches() { return minTimeBetweenTouches; }
    public void setMinTimeBetweenTouches(Duration v) { this.minTimeBetweenTouches = v; }
    public double getBreakoutTolerancePercent() { return breakoutTolerancePercent; }
    public void setBreakoutTolerancePercent(double v) { this.breakoutTolerancePercent = v; }
    public int getConfirmationCandles() { return confirmationCandles; }
    public void setConfirmationCandles(int v) { this.confirmationCandles = v; }
    public boolean isCloseBeyondLevel() { return closeBeyondLevel; }
    public void setCloseBeyondLevel(boolean v) { this.closeBeyondLevel = v; }
    public boolean isWickBreakAllowed() { return wickBreakAllowed; }
    public void setWickBreakAllowed(boolean v) { this.wickBreakAllowed = v; }
    public int getMinTouches() { return minTouches; }
    public void setMinTouches(int v) { this.minTouches = v; }
    public SignalTrigger getSignalTrigger() { return signalTrigger; }
    public void setSignalTrigger(SignalTrigger v) { this.signalTrigger = v; }
    public double getMinLevelStrength() { return minLevelStrength; }
    public void setMinLevelStrength(double v) { this.minLevelStrength = v; }
    public boolean isEmitSignalOnEveryTouchAfterMin() { return emitSignalOnEveryTouchAfterMin; }
    public void setEmitSignalOnEveryTouchAfterMin(boolean v) { this.emitSignalOnEveryTouchAfterMin = v; }
    public int getMaxActiveLevelsPerMarket() { return maxActiveLevelsPerMarket; }
    public void setMaxActiveLevelsPerMarket(int v) { this.maxActiveLevelsPerMarket = v; }
    public int getLevelExpiryCandles() { return levelExpiryCandles; }
    public void setLevelExpiryCandles(int v) { this.levelExpiryCandles = v; }
    public boolean isEnableRoleFlip() { return enableRoleFlip; }
    public boolean isSupersedeEnabled() { return supersedeEnabled; }
    public void setSupersedeEnabled(boolean v) { this.supersedeEnabled = v; }
    public String getSupersedeMaxDistancePercent() { return supersedeMaxDistancePercent; }
    public void setSupersedeMaxDistancePercent(String v) { this.supersedeMaxDistancePercent = v; }
    public double getSupersedeDefaultMaxDistancePercent() { return supersedeDefaultMaxDistancePercent; }
    public void setSupersedeDefaultMaxDistancePercent(double v) { this.supersedeDefaultMaxDistancePercent = v; }
    public void setEnableRoleFlip(boolean v) { this.enableRoleFlip = v; }
    public double getStrengthTouchWeight() { return strengthTouchWeight; }
    public void setStrengthTouchWeight(double v) { this.strengthTouchWeight = v; }
    public double getStrengthRejectionWeight() { return strengthRejectionWeight; }
    public void setStrengthRejectionWeight(double v) { this.strengthRejectionWeight = v; }
    public double getStrengthSpanWeight() { return strengthSpanWeight; }
    public void setStrengthSpanWeight(double v) { this.strengthSpanWeight = v; }
}
