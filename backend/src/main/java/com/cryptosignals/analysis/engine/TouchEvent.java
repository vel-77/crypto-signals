package com.cryptosignals.analysis.engine;

/** What a single candle did to a level's touch state machine. */
public enum TouchEvent {
    /** Nothing relevant happened. */
    NONE,
    /** A new, separate touch began on this candle. */
    OPENED,
    /** Price is still inside the zone, extending the current touch. */
    EXTENDED,
    /** The previous touch completed its separation criteria; the level is armed again. */
    CLOSED
}
