package com.cryptosignals.persistence.jdbc;

import java.time.Instant;

/**
 * A hole in a stored series.
 *
 * @param afterOpenTime  last candle present before the hole
 * @param beforeOpenTime first candle present after the hole
 * @param missingCandles how many candles of the market's timeframe are absent
 */
public record CandleGap(Instant afterOpenTime, Instant beforeOpenTime, long missingCandles) {
}
