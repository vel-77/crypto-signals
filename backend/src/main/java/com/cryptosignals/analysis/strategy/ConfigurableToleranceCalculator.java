package com.cryptosignals.analysis.strategy;

import com.cryptosignals.analysis.AnalysisConfig;

/**
 * Standard tolerance implementation honouring {@link com.cryptosignals.domain.ToleranceMode}.
 *
 * <p>Every mode is scale-free: a percentage is relative by definition and ATR is denominated in the
 * instrument's own price units, so a 0.1% zone behaves identically on a $0.0001 coin and on a
 * $100 000 one. That is the property section 2 of the specification asks for.
 *
 * <p>Until the ATR has enough candles to be meaningful, ATR-based modes fall back to the percentage
 * so that startup behaves predictably rather than producing zero-width zones.
 */
public final class ConfigurableToleranceCalculator implements ToleranceCalculator {

    @Override
    public String name() {
        return "configurable";
    }

    @Override
    public double halfWidth(double levelPrice, double atr, AnalysisConfig config) {
        double percentWidth = levelPrice * config.touchTolerancePercent() / 100.0;
        boolean atrUsable = Double.isFinite(atr) && atr > 0;
        double atrWidth = atrUsable ? atr * config.atrToleranceMultiplier() : Double.NaN;

        return switch (config.toleranceMode()) {
            case PERCENT -> percentWidth;
            case ATR -> atrUsable ? atrWidth : percentWidth;
            case MAX_OF_BOTH -> atrUsable ? Math.max(percentWidth, atrWidth) : percentWidth;
        };
    }
}
