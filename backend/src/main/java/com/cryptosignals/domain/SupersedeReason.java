package com.cryptosignals.domain;

/** Why a level was SUPERSEDED. Both mean "hidden in favour of another level", never "broken". */
public enum SupersedeReason {
    /**
     * Another established level of the same role sits within the timeframe's grouping distance,
     * further out: higher for resistance, lower for support. Only the outermost level of such a
     * group stays active, whatever the current price.
     */
    NEARBY_LEVEL,
    /**
     * Price moved past this level towards an established level of the same role just beyond it,
     * and that level became the nearer one to price.
     */
    PRICE_PASSED
}
