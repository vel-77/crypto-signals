package com.cryptosignals.domain;

import java.time.Instant;

/**
 * A single immutable OHLCV candlestick.
 *
 * <p>Only <b>closed</b> candles ever reach the analysis engine; partial (in-progress) candles are
 * filtered out at the market-data boundary. Timestamps are epoch millis for speed and exact
 * comparability; prices are {@code double}, which gives ~15-16 significant digits and is therefore
 * safe across the entire realistic crypto price range (1e-9 .. 1e7). Because every comparison in
 * the engine is tolerance-based rather than an equality check, and because candles are processed
 * strictly sequentially on one thread, {@code double} arithmetic is fully deterministic here.
 *
 * @param openTime  inclusive start of the candle interval, epoch millis
 * @param closeTime inclusive end of the candle interval, epoch millis (exchange convention)
 */
public record Candle(
        long openTime,
        long closeTime,
        double open,
        double high,
        double low,
        double close,
        double volume) {

    public Candle {
        if (openTime <= 0) {
            throw new IllegalArgumentException("openTime must be positive, got " + openTime);
        }
        if (closeTime <= openTime) {
            throw new IllegalArgumentException(
                    "closeTime (" + closeTime + ") must be after openTime (" + openTime + ")");
        }
        requireFinitePositive(open, "open");
        requireFinitePositive(high, "high");
        requireFinitePositive(low, "low");
        requireFinitePositive(close, "close");
        if (!Double.isFinite(volume) || volume < 0) {
            throw new IllegalArgumentException("volume must be finite and non-negative, got " + volume);
        }
        if (high < low) {
            throw new IllegalArgumentException("high (" + high + ") must be >= low (" + low + ")");
        }
        if (high < open || high < close) {
            throw new IllegalArgumentException("high (" + high + ") must be >= open and close");
        }
        if (low > open || low > close) {
            throw new IllegalArgumentException("low (" + low + ") must be <= open and close");
        }
    }

    private static void requireFinitePositive(double value, String field) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(field + " must be finite and > 0, got " + value);
        }
    }

    public Instant openInstant() {
        return Instant.ofEpochMilli(openTime);
    }

    public Instant closeInstant() {
        return Instant.ofEpochMilli(closeTime);
    }

    /** Full high-low range. */
    public double range() {
        return high - low;
    }

    /** Upper edge of the candle body. */
    public double bodyHigh() {
        return Math.max(open, close);
    }

    /** Lower edge of the candle body. */
    public double bodyLow() {
        return Math.min(open, close);
    }

    public boolean bullish() {
        return close >= open;
    }
}
