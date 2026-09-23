package com.cryptosignals.domain;

/** Whether a discovered zone acts as a ceiling or a floor for price. */
public enum LevelType {
    /** Price approaches from below and is rejected downwards. Built from swing highs. */
    RESISTANCE,
    /** Price approaches from above and is rejected upwards. Built from swing lows. */
    SUPPORT;

    public LevelType opposite() {
        return this == RESISTANCE ? SUPPORT : RESISTANCE;
    }

    /**
     * Sign of the direction a breakout travels: +1 for resistance (upwards), -1 for support.
     */
    public int breakoutDirection() {
        return this == RESISTANCE ? 1 : -1;
    }
}
