package com.cryptosignals.config;

import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Which markets to watch and how much history to analyse.
 *
 * <p>The watch list is the cross product of {@link #symbols} and {@link #timeframes}: each pair is
 * an independent engine with its own levels and its own signals.
 */
@ConfigurationProperties(prefix = "crypto-signals.markets")
public class MarketProperties {

    private List<String> symbols = new ArrayList<>(List.of("BTCUSDT"));
    private List<String> timeframes = new ArrayList<>(List.of("5m", "1h"));

    /**
     * How far back to analyse. {@link Duration#ZERO} means "everything the exchange will give us",
     * which the backfiller walks page by page rather than requesting in one call.
     */
    private Duration lookback = Duration.ofDays(30);

    /** Hard ceiling on candles fetched per market during backfill; a guard against runaway pulls. */
    private int maxCandlesPerBackfill = 200_000;

    private boolean backfillOnStartup = true;
    private boolean liveEnabled = true;

    /** Re-check for gaps and missed candles on this interval while live. */
    private Duration reconciliationInterval = Duration.ofMinutes(15);

    /** Expands the configured symbols and timeframes into concrete markets. */
    public List<MarketKey> toMarketKeys() {
        List<MarketKey> keys = new ArrayList<>();
        for (String symbol : symbols) {
            for (String timeframe : timeframes) {
                keys.add(new MarketKey(symbol, Timeframe.fromCode(timeframe)));
            }
        }
        return keys;
    }

    public boolean isUnlimitedLookback() {
        return lookback == null || lookback.isZero() || lookback.isNegative();
    }

    public List<String> getSymbols() { return symbols; }
    public void setSymbols(List<String> v) { this.symbols = v; }
    public List<String> getTimeframes() { return timeframes; }
    public void setTimeframes(List<String> v) { this.timeframes = v; }
    public Duration getLookback() { return lookback; }
    public void setLookback(Duration v) { this.lookback = v; }
    public int getMaxCandlesPerBackfill() { return maxCandlesPerBackfill; }
    public void setMaxCandlesPerBackfill(int v) { this.maxCandlesPerBackfill = v; }
    public boolean isBackfillOnStartup() { return backfillOnStartup; }
    public void setBackfillOnStartup(boolean v) { this.backfillOnStartup = v; }
    public boolean isLiveEnabled() { return liveEnabled; }
    public void setLiveEnabled(boolean v) { this.liveEnabled = v; }
    public Duration getReconciliationInterval() { return reconciliationInterval; }
    public void setReconciliationInterval(Duration v) { this.reconciliationInterval = v; }
}
