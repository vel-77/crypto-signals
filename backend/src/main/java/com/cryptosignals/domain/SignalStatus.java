package com.cryptosignals.domain;

/** Lifecycle of an emitted signal. */
public enum SignalStatus {
    /** Emitted, and the underlying level has not been broken since. */
    ACTIVE,
    /** The underlying level was broken after the signal was emitted. */
    INVALIDATED,
    /** The underlying level aged out without a breakout. */
    EXPIRED
}
