package com.cryptosignals.analysis.model;

/**
 * One <em>separate</em> interaction between price and a level's zone.
 *
 * <p>A touch spans a run of candles: it opens when price first enters the zone while the level is
 * armed, and closes only once the configured separation criteria are met (see
 * {@code docs/ALGORITHM.md}). Consecutive candles sitting in the zone therefore collapse into a
 * single touch, which is the rule demanded in section 3 of the specification.
 *
 * @param touchNumber                1-based ordinal of this touch on its level
 * @param startIndex                 first candle of the interaction
 * @param endIndex                   last candle of the interaction (== startIndex while open)
 * @param extremeIndex               candle that reached furthest into/through the zone
 * @param startTime                  open time of the first candle, epoch millis
 * @param endTime                    open time of the last candle, epoch millis
 * @param extremeTime                open time of the extreme candle, epoch millis
 * @param extremePrice               furthest price reached (high for resistance, low for support)
 * @param levelPriceAtTouch          level price at the moment of the touch (levels refine over time)
 * @param distanceFromLevel          signed {@code extremePrice - levelPriceAtTouch}
 * @param distancePercent            {@code |distanceFromLevel| / levelPriceAtTouch * 100}
 * @param candlesSincePreviousTouch  candles between the previous interaction's end and this one's
 *                                   start; -1 for the first touch
 * @param millisSincePreviousTouch   wall-clock gap from the previous touch's extreme; -1 for the first
 * @param priceDeltaFromPreviousTouch signed extreme-to-extreme price change; 0 for the first touch
 * @param rejectionPercent           furthest price travelled away from the level after this touch,
 *                                   as a percent of the level price; 0 while the touch is still open
 * @param closed                     whether the interaction has completed its separation criteria
 */
public record Touch(
        int touchNumber,
        int startIndex,
        int endIndex,
        int extremeIndex,
        long startTime,
        long endTime,
        long extremeTime,
        double extremePrice,
        double levelPriceAtTouch,
        double distanceFromLevel,
        double distancePercent,
        int candlesSincePreviousTouch,
        long millisSincePreviousTouch,
        double priceDeltaFromPreviousTouch,
        double rejectionPercent,
        boolean closed) {

    /** Number of candles this interaction occupied. */
    public int candleCount() {
        return endIndex - startIndex + 1;
    }
}
