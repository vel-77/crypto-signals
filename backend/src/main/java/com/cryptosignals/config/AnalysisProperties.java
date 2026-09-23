package com.cryptosignals.config;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.domain.PriceAggregation;
import com.cryptosignals.domain.SignalTrigger;
import com.cryptosignals.domain.ToleranceMode;
import com.cryptosignals.domain.TouchMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

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

    // --- strength weights
    @PositiveOrZero private double strengthTouchWeight = 0.5;
    @PositiveOrZero private double strengthRejectionWeight = 0.3;
    @PositiveOrZero private double strengthSpanWeight = 0.2;

    /** Builds the immutable engine configuration, applying its stricter cross-field validation. */
    public AnalysisConfig toAnalysisConfig() {
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
                .strengthTouchWeight(strengthTouchWeight)
                .strengthRejectionWeight(strengthRejectionWeight)
                .strengthSpanWeight(strengthSpanWeight)
                .build();
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
    public void setEnableRoleFlip(boolean v) { this.enableRoleFlip = v; }
    public double getStrengthTouchWeight() { return strengthTouchWeight; }
    public void setStrengthTouchWeight(double v) { this.strengthTouchWeight = v; }
    public double getStrengthRejectionWeight() { return strengthRejectionWeight; }
    public void setStrengthRejectionWeight(double v) { this.strengthRejectionWeight = v; }
    public double getStrengthSpanWeight() { return strengthSpanWeight; }
    public void setStrengthSpanWeight(double v) { this.strengthSpanWeight = v; }
}
