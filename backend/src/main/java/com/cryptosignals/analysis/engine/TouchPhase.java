package com.cryptosignals.analysis.engine;

/**
 * The per-level state machine that turns a stream of candles into <em>separate</em> touches.
 * This is the mechanism that stops four consecutive candles sitting on a level from counting as
 * four touches (specification section 3).
 */
public enum TouchPhase {
    /** Outside the zone and separation satisfied: the next entry starts a brand-new touch. */
    ARMED,
    /** Price is currently inside the zone; further candles extend the same touch. */
    IN_TOUCH,
    /** Price has left the zone but has not yet satisfied the separation rules. */
    DEPARTING
}
