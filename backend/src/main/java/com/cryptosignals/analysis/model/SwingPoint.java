package com.cryptosignals.analysis.model;

import com.cryptosignals.domain.LevelType;

/**
 * A confirmed local extreme.
 *
 * @param index         index of the candle that printed the extreme, within the analysed series
 * @param time          open time of that candle, epoch millis
 * @param price         the extreme price (high for a swing high, low for a swing low)
 * @param type          RESISTANCE for swing highs, SUPPORT for swing lows
 * @param confirmedAtIndex index of the candle at which this swing became <em>knowable</em>, i.e.
 *                      {@code index + swingRightBars}. Recording this makes the look-ahead
 *                      latency explicit and keeps backtest and live behaviour identical.
 */
public record SwingPoint(int index, long time, double price, LevelType type, int confirmedAtIndex) {
}
