package com.cryptosignals.domain;

/** Lifecycle of a discovered price level. */
public enum LevelStatus {
    /** Still valid and accumulating touches. */
    ACTIVE,
    /** A breakout was confirmed under the configured rules; the level no longer accumulates touches. */
    BROKEN,
    /** Aged out without being broken (only when level expiry is enabled). */
    EXPIRED
}
