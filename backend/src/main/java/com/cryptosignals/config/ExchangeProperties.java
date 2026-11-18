package com.cryptosignals.config;

import com.cryptosignals.exchange.ExchangeSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Exchange connectivity. Each market uses its own addresses and request budget (Binance limits
 * spot and futures separately); the settings here are shared by all of them.
 */
@ConfigurationProperties(prefix = "crypto-signals.exchange")
public class ExchangeProperties {

    /**
     * The markets to run, each on its own: BINANCE (spot) and / or BINANCE_FUTURES (USDⓈ-M
     * perpetual futures). The first is the default the API answers for and the web interface
     * opens on.
     */
    private List<String> venues = new ArrayList<>(List.of("BINANCE"));

    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(30);

    /** Candles requested per REST page. Binance caps this at 1000. */
    private int klinePageSize = 1000;

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
     * Adapts external configuration into the exchange layer's own settings type, for one market:
     * its addresses and its request budget.
     */
    public ExchangeSettings toSettings(String restBaseUrl, String wsBaseUrl, int maxWeightPerMinute) {
        return new ExchangeSettings(restBaseUrl, wsBaseUrl,
                connectTimeout, readTimeout, klinePageSize, maxWeightPerMinute,
                maxRetries, retryBackoff, reconnectMinBackoff, reconnectMaxBackoff, streamStaleAfter,
                symbolTagsUrl);
    }

    public List<String> getVenues() { return venues; }
    /** Stored markets are filed under these names, so they are kept in one spelling. */
    public void setVenues(List<String> v) {
        Set<String> names = new LinkedHashSet<>();
        if (v != null) {
            for (String name : v) {
                if (name != null && !name.isBlank()) {
                    names.add(name.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        this.venues = new ArrayList<>(names);
    }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration v) { this.connectTimeout = v; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration v) { this.readTimeout = v; }
    public int getKlinePageSize() { return klinePageSize; }
    public void setKlinePageSize(int v) { this.klinePageSize = v; }
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
