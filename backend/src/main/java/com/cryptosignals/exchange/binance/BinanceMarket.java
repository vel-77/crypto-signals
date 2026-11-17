package com.cryptosignals.exchange.binance;

import java.util.Locale;
import java.util.Optional;

/**
 * The two Binance markets the client can read. They share the wire format of candles and of the
 * live stream; they differ in addresses, request weights and how the coin list is worked out.
 */
public enum BinanceMarket {

    /** Binance spot. */
    SPOT("BINANCE", "Binance spot", "/api/v3",
            "https://api.binance.com", "wss://stream.binance.com:9443",
            // 2400 of the 6000 a minute Binance allows spot, leaving room for anything else.
            2400),

    /**
     * Binance USDⓈ-M perpetual futures. Market streams are served under {@code /market}: the bare
     * {@code wss://fstream.binance.com} still accepts connections but sends no candles.
     */
    USDM_FUTURES("BINANCE_FUTURES", "Binance USDⓈ-M futures", "/fapi/v1",
            "https://fapi.binance.com", "wss://fstream.binance.com/market",
            // Half the 2400 a minute Binance allows futures.
            1200);

    private final String exchangeName;
    private final String label;
    private final String apiPath;
    private final String defaultRestBaseUrl;
    private final String defaultWsBaseUrl;
    private final int defaultMaxWeightPerMinute;

    BinanceMarket(String exchangeName, String label, String apiPath, String defaultRestBaseUrl,
                  String defaultWsBaseUrl, int defaultMaxWeightPerMinute) {
        this.exchangeName = exchangeName;
        this.label = label;
        this.apiPath = apiPath;
        this.defaultRestBaseUrl = defaultRestBaseUrl;
        this.defaultWsBaseUrl = defaultWsBaseUrl;
        this.defaultMaxWeightPerMinute = defaultMaxWeightPerMinute;
    }

    /** What {@code crypto-signals.exchange.name} says, and what stored markets are filed under. */
    public String exchangeName() {
        return exchangeName;
    }

    public String label() {
        return label;
    }

    /** The REST path prefix, e.g. {@code /api/v3}. */
    public String apiPath() {
        return apiPath;
    }

    public String defaultRestBaseUrl() {
        return defaultRestBaseUrl;
    }

    public String defaultWsBaseUrl() {
        return defaultWsBaseUrl;
    }

    public int defaultMaxWeightPerMinute() {
        return defaultMaxWeightPerMinute;
    }

    /** Request weight of one klines call returning up to {@code limit} candles. */
    public int klinesWeight(int limit) {
        if (this == SPOT) {
            return 2;
        }
        if (limit < 100) {
            return 1;
        }
        if (limit < 500) {
            return 2;
        }
        return limit <= 1000 ? 5 : 10;
    }

    /** Request weight of one exchangeInfo call: spot's is per symbol, futures' lists everything. */
    public int exchangeInfoWeight() {
        return this == SPOT ? 20 : 1;
    }

    public static Optional<BinanceMarket> byExchangeName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name.trim().toUpperCase(Locale.ROOT);
        for (BinanceMarket market : values()) {
            if (market.exchangeName.equals(wanted)) {
                return Optional.of(market);
            }
        }
        return Optional.empty();
    }
}
