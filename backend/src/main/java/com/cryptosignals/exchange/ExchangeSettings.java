package com.cryptosignals.exchange;

import java.time.Duration;

/**
 * Connectivity settings an {@link ExchangeClient} needs, owned by the exchange layer itself.
 *
 * <p>Exchange clients deliberately do not read Spring configuration properties. If they did, the
 * exchange package and the configuration package would depend on each other — configuration
 * constructs the client, the client reads configuration — and that cycle is exactly what makes a
 * "modular" codebase gradually stop being one. The configuration layer adapts its external
 * properties into this type instead, so the dependency runs one way and an exchange client can be
 * constructed in a unit test with a plain record and no Spring at all.
 */
public record ExchangeSettings(
        String restBaseUrl,
        String wsBaseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        int klinePageSize,
        int maxWeightPerMinute,
        int maxRetries,
        Duration retryBackoff,
        Duration reconnectMinBackoff,
        Duration reconnectMaxBackoff,
        Duration streamStaleAfter,
        String symbolTagsUrl) {

    /** Sensible Binance-shaped defaults, handy for tests. */
    public static ExchangeSettings defaults() {
        return new ExchangeSettings(
                "https://api.binance.com", "wss://stream.binance.com:9443",
                Duration.ofSeconds(10), Duration.ofSeconds(30),
                1000, 2400, 5, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofMinutes(2), Duration.ofSeconds(90),
                DEFAULT_SYMBOL_TAGS_URL);
    }

    /**
     * Binance's public product list, the one its own markets page reads. It is the only place that
     * says which symbols are stablecoins, tokenized stocks or commodities.
     */
    public static final String DEFAULT_SYMBOL_TAGS_URL =
            "https://www.binance.com/bapi/asset/v2/public/asset-service/product/get-products?includeEtf=true";

    public ExchangeSettings withWsBaseUrl(String url) {
        return new ExchangeSettings(restBaseUrl, url, connectTimeout, readTimeout, klinePageSize,
                maxWeightPerMinute, maxRetries, retryBackoff, reconnectMinBackoff, reconnectMaxBackoff,
                streamStaleAfter, symbolTagsUrl);
    }

    public ExchangeSettings withRestBaseUrl(String url) {
        return new ExchangeSettings(url, wsBaseUrl, connectTimeout, readTimeout, klinePageSize,
                maxWeightPerMinute, maxRetries, retryBackoff, reconnectMinBackoff, reconnectMaxBackoff,
                streamStaleAfter, symbolTagsUrl);
    }

    public ExchangeSettings withSymbolTagsUrl(String url) {
        return new ExchangeSettings(restBaseUrl, wsBaseUrl, connectTimeout, readTimeout, klinePageSize,
                maxWeightPerMinute, maxRetries, retryBackoff, reconnectMinBackoff, reconnectMaxBackoff,
                streamStaleAfter, url);
    }
}
