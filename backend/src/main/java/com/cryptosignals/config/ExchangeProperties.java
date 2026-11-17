package com.cryptosignals.config;

import com.cryptosignals.exchange.ExchangeSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Exchange connectivity. Addresses and the weight budget left empty (or 0) take the chosen venue's
 * own defaults, so switching {@link #name} is all it takes to move between spot and futures.
 */
@ConfigurationProperties(prefix = "crypto-signals.exchange")
public class ExchangeProperties {

    /**
     * Which {@code ExchangeClient} implementation to activate, matched against its name: BINANCE
     * (spot) or BINANCE_FUTURES (USDⓈ-M perpetual futures).
     */
    private String name = "BINANCE";

    /** Empty means the venue's own address. */
    private String restBaseUrl = "";
    private String wsBaseUrl = "";

    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(30);

    /** Candles requested per REST page. Binance caps this at 1000. */
    private int klinePageSize = 1000;

    /**
     * Request-weight budget per minute; 0 means the venue's default, which leaves generous headroom
     * under what Binance allows (6000/min on spot, 2400/min on futures) so a backfill never trips a ban.
     */
    private int maxWeightPerMinute = 0;

    private int maxRetries = 5;
    private Duration retryBackoff = Duration.ofSeconds(1);

    /** Websocket reconnect backoff bounds. */
    private Duration reconnectMinBackoff = Duration.ofSeconds(1);
    private Duration reconnectMaxBackoff = Duration.ofMinutes(2);

    /**
     * A live stream that delivers nothing for this long is treated as dead and reconnected.
     * Binance pushes each kline stream every ~2 seconds, so this is many missed updates.
     */
    private Duration streamStaleAfter = Duration.ofSeconds(90);

    /**
     * Where the venue publishes its category tags (stablecoin, tokenized stock, ...), used when the
     * watch list is chosen by volume. Empty disables the lookup, and with it the tag exclusions.
     */
    private String symbolTagsUrl = ExchangeSettings.DEFAULT_SYMBOL_TAGS_URL;

    /**
     * Adapts external configuration into the exchange layer's own settings type, filling in the
     * venue's defaults wherever nothing was set.
     */
    public ExchangeSettings toSettings(String defaultRestBaseUrl, String defaultWsBaseUrl,
                                       int defaultMaxWeightPerMinute) {
        return new ExchangeSettings(
                isBlank(restBaseUrl) ? defaultRestBaseUrl : restBaseUrl.trim(),
                isBlank(wsBaseUrl) ? defaultWsBaseUrl : wsBaseUrl.trim(),
                connectTimeout, readTimeout, klinePageSize,
                maxWeightPerMinute > 0 ? maxWeightPerMinute : defaultMaxWeightPerMinute,
                maxRetries, retryBackoff, reconnectMinBackoff, reconnectMaxBackoff, streamStaleAfter,
                symbolTagsUrl);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public String getName() { return name; }
    /** Stored markets are filed under this name, so it is kept in one spelling. */
    public void setName(String v) { this.name = v == null ? null : v.trim().toUpperCase(java.util.Locale.ROOT); }
    public String getRestBaseUrl() { return restBaseUrl; }
    public void setRestBaseUrl(String v) { this.restBaseUrl = v; }
    public String getWsBaseUrl() { return wsBaseUrl; }
    public void setWsBaseUrl(String v) { this.wsBaseUrl = v; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration v) { this.connectTimeout = v; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration v) { this.readTimeout = v; }
    public int getKlinePageSize() { return klinePageSize; }
    public void setKlinePageSize(int v) { this.klinePageSize = v; }
    public int getMaxWeightPerMinute() { return maxWeightPerMinute; }
    public void setMaxWeightPerMinute(int v) { this.maxWeightPerMinute = v; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int v) { this.maxRetries = v; }
    public Duration getRetryBackoff() { return retryBackoff; }
    public void setRetryBackoff(Duration v) { this.retryBackoff = v; }
    public Duration getReconnectMinBackoff() { return reconnectMinBackoff; }
    public void setReconnectMinBackoff(Duration v) { this.reconnectMinBackoff = v; }
    public Duration getReconnectMaxBackoff() { return reconnectMaxBackoff; }
    public Duration getStreamStaleAfter() { return streamStaleAfter; }
    public void setStreamStaleAfter(Duration v) { this.streamStaleAfter = v; }
    public void setReconnectMaxBackoff(Duration v) { this.reconnectMaxBackoff = v; }
    public String getSymbolTagsUrl() { return symbolTagsUrl; }
    public void setSymbolTagsUrl(String v) { this.symbolTagsUrl = v; }
}
