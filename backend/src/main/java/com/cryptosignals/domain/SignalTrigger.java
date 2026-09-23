package com.cryptosignals.domain;

/** When the Nth touch is allowed to emit a signal. */
public enum SignalTrigger {
    /**
     * Emit as soon as price enters the zone for the Nth time and no breakout is confirmed on that
     * candle. Timely, but the touch may still fail immediately afterwards (the signal is then
     * marked INVALIDATED). This is the default for an alerting system.
     */
    ON_TOUCH,
    /**
     * Emit only once the Nth interaction has completed and price has demonstrably rejected away
     * from the zone by the configured departure distance. Later, but the pattern is confirmed.
     */
    ON_CONFIRMED_REJECTION
}
