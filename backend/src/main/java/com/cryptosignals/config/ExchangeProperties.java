package com.cryptosignals.config;

import com.cryptosignals.exchange.ExchangeSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Exchange connectivity. Values are venue-agnostic; the Binance client supplies its own defaults. */
@ConfigurationProperties(prefix = "crypto-signals.exchange")
public class ExchangeProperties {

    /** Which {@code ExchangeClient} implementation to activate, matched against its name. */
    private String name = "BINANCE";

    private String restBaseUrl = "https://api.binance.com";
    private String wsBaseUrl = "wss://stream.binance.com:9443";

    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(30);

    /** Candles requested per REST page. Binance caps this at 1000. */
    private int klinePageSize = 1000;

    /**
     * Request-weight budget per minute. Binance publishes 6000/min for the spot REST API; the
     * default leaves generous headroom so a backfill never trips a ban.
     */
    private int maxWeightPerMinute = 2400;

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

    /** Adapts external configuration into the exchange layer's own settings type. */
    public ExchangeSettings toSettings() {
        return new ExchangeSettings(restBaseUrl, wsBaseUrl, connectTimeout, readTimeout,
                klinePageSize, maxWeightPerMinute, maxRetries, retryBackoff,
                reconnectMinBackoff, reconnectMaxBackoff, streamStaleAfter);
    }

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
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
}
