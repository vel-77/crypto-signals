package com.cryptosignals.domain;

/**
 * Identity of an analysed series. A signal on BTCUSDT 1m is entirely independent from a signal on
 * BTCUSDT 5m, so the market key is the unit of isolation for engine state everywhere.
 */
public record MarketKey(String symbol, Timeframe timeframe) {

    public MarketKey {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        symbol = symbol.trim().toUpperCase();
        if (timeframe == null) {
            throw new IllegalArgumentException("timeframe must not be null");
        }
    }

    public static MarketKey of(String symbol, String timeframeCode) {
        return new MarketKey(symbol, Timeframe.fromCode(timeframeCode));
    }

    @Override
    public String toString() {
        return symbol + ":" + timeframe.code();
    }
}
