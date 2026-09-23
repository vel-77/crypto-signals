package com.cryptosignals.domain;

/**
 * Which part of a candle must reach the zone for the candle to count as being "in the zone".
 *
 * <p>This is one of the tunable interpretations of the core business rule ("price reached the
 * level"). See {@code docs/ALGORITHM.md}.
 */
public enum TouchMode {
    /** The candle's full high-low range overlaps the zone. Most permissive, the default. */
    WICK,
    /** The candle's body (open-close) overlaps the zone. Ignores long rejection wicks. */
    BODY,
    /** The candle's close is inside the zone. Most conservative. */
    CLOSE
}
