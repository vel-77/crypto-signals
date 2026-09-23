package com.cryptosignals.domain;

/** How a cluster of swing points collapses into a single representative level price. */
public enum PriceAggregation {
    /** Arithmetic mean of member swing prices. Default: stable and smooth. */
    MEAN,
    /** Median of member swing prices. More robust to a single outlier swing. */
    MEDIAN,
    /** The most extreme member: highest high for resistance, lowest low for support. */
    EXTREME
}
