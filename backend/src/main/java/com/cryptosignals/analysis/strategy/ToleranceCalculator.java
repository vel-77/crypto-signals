package com.cryptosignals.analysis.strategy;

import com.cryptosignals.analysis.AnalysisConfig;

/**
 * Derives the half-width of a price zone. Swapping the implementation changes what "the same price
 * zone" means without touching the rest of the engine.
 */
public interface ToleranceCalculator {

    String name();

    /**
     * @param levelPrice the representative price of the level
     * @param atr        current ATR, or {@link Double#NaN} if not yet warmed up
     * @return half-width in absolute price units; the zone is {@code price +/- halfWidth}
     */
    double halfWidth(double levelPrice, double atr, AnalysisConfig config);
}
