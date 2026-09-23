package com.cryptosignals.analysis.model;

import com.cryptosignals.domain.LevelType;

/**
 * A confirmed breakout that invalidates a level.
 *
 * @param levelId             the level that was broken
 * @param levelType           the role the level had when it broke
 * @param levelPrice          level price at the moment of the break
 * @param thresholdPrice      the price that had to be exceeded, i.e.
 *                            {@code levelPrice * (1 +/- breakoutTolerancePercent/100)}
 * @param firstBreachIndex    first candle of the confirming run
 * @param confirmedAtIndex    candle at which the run reached {@code confirmationCandles}
 * @param confirmedAtTime     open time of that candle, epoch millis
 * @param confirmationPrice   close of the confirming candle
 * @param confirmationCandles how many consecutive candles were required
 * @param touchCountAtBreak   how many valid touches the level had accumulated before breaking
 */
public record BreakoutEvent(
        String levelId,
        LevelType levelType,
        double levelPrice,
        double thresholdPrice,
        int firstBreachIndex,
        int confirmedAtIndex,
        long confirmedAtTime,
        double confirmationPrice,
        int confirmationCandles,
        int touchCountAtBreak) {
}
