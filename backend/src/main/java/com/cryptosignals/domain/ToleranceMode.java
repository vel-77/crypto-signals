package com.cryptosignals.domain;

/** How the half-width of a price zone is derived. All modes are scale-free. */
public enum ToleranceMode {
    /** tolerance = level * percent/100. Purely relative, identical behaviour at any price scale. */
    PERCENT,
    /** tolerance = atrMultiplier * ATR. Adapts to the instrument's current volatility. */
    ATR,
    /** tolerance = max(PERCENT, ATR). Percentage acts as a floor when volatility collapses. */
    MAX_OF_BOTH
}
