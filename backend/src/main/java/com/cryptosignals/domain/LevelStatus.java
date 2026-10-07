package com.cryptosignals.domain;

/** Lifecycle of a discovered price level. */
public enum LevelStatus {
    /** Still valid and accumulating touches. */
    ACTIVE,
    /** A breakout was confirmed under the configured rules; the level no longer accumulates touches. */
    BROKEN,
    /** Aged out without being broken (only when level expiry is enabled). */
    EXPIRED,
    /**
     * Price moved past this level and a stronger level of the same role, close by on the far
     * side, is now nearer to price. The level is retired: it stops counting touches and stops
     * signalling, but a later breakout is still recorded against it.
     */
    SUPERSEDED
}
