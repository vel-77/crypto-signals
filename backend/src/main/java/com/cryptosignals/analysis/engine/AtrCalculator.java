package com.cryptosignals.analysis.engine;

import com.cryptosignals.domain.Candle;

/**
 * Incremental Wilder ATR.
 *
 * <p>Seeded with the simple average of the first {@code period} true ranges, then smoothed with
 * Wilder's recursion. Feeding the same candles in the same order always yields the same value, so
 * volatility-based tolerances stay deterministic.
 */
public final class AtrCalculator {

    private final int period;
    private double sumSeed;
    private int seedCount;
    private double atr = Double.NaN;
    private Double previousClose;

    public AtrCalculator(int period) {
        if (period < 1) {
            throw new IllegalArgumentException("ATR period must be >= 1, got " + period);
        }
        this.period = period;
    }

    public void update(Candle candle) {
        double trueRange = previousClose == null
                ? candle.high() - candle.low()
                : Math.max(candle.high() - candle.low(),
                        Math.max(Math.abs(candle.high() - previousClose),
                                Math.abs(candle.low() - previousClose)));
        previousClose = candle.close();

        if (seedCount < period) {
            sumSeed += trueRange;
            seedCount++;
            if (seedCount == period) {
                atr = sumSeed / period;
            }
            return;
        }
        atr = (atr * (period - 1) + trueRange) / period;
    }

    /** Current ATR, or {@link Double#NaN} until {@code period} candles have been seen. */
    public double value() {
        return atr;
    }

    public boolean isWarm() {
        return !Double.isNaN(atr);
    }
}
