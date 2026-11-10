package com.cryptosignals.exchange;

import java.util.Set;

/**
 * How actively one symbol traded over a trailing window, as reported by the venue.
 *
 * @param symbol      the venue's symbol, e.g. {@code SOLUSDT}
 * @param baseAsset   what is bought and sold, e.g. {@code SOL}
 * @param quoteVolume traded value over the window, in the quote asset
 * @param highPrice   highest price over the window
 * @param lowPrice    lowest price over the window
 * @param tags        the venue's own categories for the symbol, e.g. {@code stablecoin}; empty if
 *                    it publishes none
 */
public record SymbolActivity(String symbol, String baseAsset, double quoteVolume,
                             double highPrice, double lowPrice, Set<String> tags) {

    public SymbolActivity {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }

    /** How far the price moved over the window, from its low to its high, in percent. */
    public double priceRangePercent() {
        return lowPrice > 0 ? (highPrice - lowPrice) / lowPrice * 100 : 0;
    }
}
