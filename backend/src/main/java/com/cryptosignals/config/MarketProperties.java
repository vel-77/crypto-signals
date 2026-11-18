package com.cryptosignals.config;

import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which markets to watch and how much history to analyse.
 *
 * <p>The watch list is the cross product of the watched symbols and {@link #timeframes}: each pair
 * is an independent engine with its own levels and its own signals. The watched symbols are
 * {@link #symbols}, which are always watched, plus — when {@link VolumeSelection#enabled} — the most
 * traded other coins, chosen from the exchange's volumes and refreshed while running.
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

    /** Chooses further symbols by trading volume. */
    private VolumeSelection volumeSelection = new VolumeSelection();

    /** Expands the configured symbols and timeframes into concrete markets. */
    public List<MarketKey> toMarketKeys() {
        return toMarketKeys(fixedSymbols());
    }

    /** Expands the given symbols and the configured timeframes into concrete markets. */
    public List<MarketKey> toMarketKeys(Collection<String> watchedSymbols) {
        List<MarketKey> keys = new ArrayList<>();
        for (String symbol : watchedSymbols) {
            for (String timeframe : timeframes) {
                keys.add(new MarketKey(symbol, Timeframe.fromCode(timeframe)));
            }
        }
        return keys;
    }

    /** The always-watched symbols, upper-cased, blanks and repeats dropped, in configured order. */
    public List<String> fixedSymbols() {
        Set<String> fixed = new LinkedHashSet<>();
        if (symbols != null) {
            for (String symbol : symbols) {
                if (symbol != null && !symbol.isBlank()) {
                    fixed.add(symbol.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return List.copyOf(fixed);
    }

    public List<Timeframe> watchedTimeframes() {
        return timeframes.stream().map(Timeframe::fromCode).toList();
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
    public VolumeSelection getVolumeSelection() { return volumeSelection; }
    public void setVolumeSelection(VolumeSelection v) { this.volumeSelection = v; }

    /**
     * Watches the most traded coins on top of the fixed symbols.
     *
     * <p>Volume is the average traded value per day over {@link #window}, in the quote asset. A coin
     * joins when that reaches the market's minimum ({@link #minDailyVolumeFor}). If that makes
     * fewer than {@link #minSymbols} coins in all, the next most traded coins fill up the list;
     * at most {@link #maxSymbols} coins are watched in all, the highest volumes first. Stablecoins,
     * tokenized stocks and the like are skipped: their prices do not form crypto support and
     * resistance.
     */
    public static class VolumeSelection {

        private boolean enabled = false;

        /** Only pairs quoted in this asset are considered; volumes are in it too. */
        private String quoteAsset = "USDT";

        /**
         * Average traded value per day a coin needs to join, in the quote asset, on a market not
         * given its own in {@link #minDailyVolumeByExchange}.
         */
        private double minDailyVolume = 5_000_000;

        /**
         * The same per market, as {@code MARKET:volume} pairs, e.g.
         * {@code BINANCE_FUTURES:300000000,BINANCE:50000000}. Markets not listed use
         * {@link #minDailyVolume}.
         */
        private String minDailyVolumeByExchange = "";
        private Map<String, Double> minDailyVolumes = Map.of();

        /**
         * How far back volume is averaged. Long enough that a quiet weekend or a one-day spike
         * does not change the list. Binance reports at most 7 days.
         */
        private Duration window = Duration.ofDays(7);

        /**
         * Fewest coins watched in all, the fixed symbols included: when fewer clear the minimum
         * volume, the next most traded coins make up the number. 0 means no minimum.
         */
        private int minSymbols = 0;

        /** Most coins watched in all, the fixed symbols included. 0 means no limit. */
        private int maxSymbols = 0;

        /**
         * A coin already watched keeps its place until its volume falls below this share, in
         * percent, of what a new coin needs, so a coin near the threshold does not join and leave
         * day after day.
         */
        private double keepPercent = 70;

        /** Coins whose price moved less than this over the window are skipped, e.g. stablecoins. */
        private double minPriceRangePercent = 1.0;

        /** The exchange's tags that rule a coin out. */
        private List<String> excludeTags = new ArrayList<>(List.of("stablecoin", "bStocks", "tCommodities"));

        /** Base assets that are never chosen: fiat, and wrapped or staked copies of other coins. */
        private List<String> excludeAssets = new ArrayList<>(List.of("EUR", "WBTC", "WBETH", "BNSOL"));

        /** How often the list is worked out again while running. */
        private Duration refreshInterval = Duration.ofHours(24);

        /** How soon to try again after the exchange could not be asked. */
        private Duration retryInterval = Duration.ofMinutes(15);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getQuoteAsset() { return quoteAsset; }
        public void setQuoteAsset(String v) { this.quoteAsset = v == null ? "USDT" : v.trim().toUpperCase(Locale.ROOT); }
        public double getMinDailyVolume() { return minDailyVolume; }
        public void setMinDailyVolume(double v) {
            if (!(v >= 0)) {
                throw new IllegalArgumentException("volume-selection.min-daily-volume must be >= 0, got " + v);
            }
            this.minDailyVolume = v;
        }
        public String getMinDailyVolumeByExchange() { return minDailyVolumeByExchange; }
        public void setMinDailyVolumeByExchange(String v) {
            Map<String, Double> parsed = new LinkedHashMap<>();
            if (v != null && !v.isBlank()) {
                for (String pair : v.split(",")) {
                    if (pair.isBlank()) {
                        continue;
                    }
                    String[] parts = pair.trim().split(":");
                    double volume;
                    try {
                        volume = parts.length == 2 ? Double.parseDouble(parts[1].trim().replace("_", "")) : -1;
                    } catch (NumberFormatException ex) {
                        volume = -1;
                    }
                    if (parts.length != 2 || parts[0].isBlank() || !(volume >= 0)) {
                        throw new IllegalArgumentException("volume-selection.min-daily-volume-by-exchange: expected "
                                + "'MARKET:volume' pairs such as 'BINANCE_FUTURES:300000000,BINANCE:50000000', got '"
                                + pair.trim() + "'");
                    }
                    parsed.put(parts[0].trim().toUpperCase(Locale.ROOT), volume);
                }
            }
            this.minDailyVolumeByExchange = v == null ? "" : v;
            this.minDailyVolumes = Map.copyOf(parsed);
        }

        /** What a coin needs to join on this market. */
        public double minDailyVolumeFor(String exchange) {
            Double own = exchange == null ? null : minDailyVolumes.get(exchange.trim().toUpperCase(Locale.ROOT));
            return own != null ? own : minDailyVolume;
        }

        public Duration getWindow() { return window; }
        public void setWindow(Duration v) {
            if (v == null || v.toMinutes() < 1 || v.compareTo(Duration.ofDays(7)) > 0) {
                throw new IllegalArgumentException(
                        "volume-selection.window must be between 1 minute and 7 days, got " + v);
            }
            this.window = v;
        }
        public int getMinSymbols() { return minSymbols; }
        public void setMinSymbols(int v) {
            if (v < 0) {
                throw new IllegalArgumentException("volume-selection.min-symbols must be >= 0, got " + v);
            }
            this.minSymbols = v;
        }
        public int getMaxSymbols() { return maxSymbols; }
        public void setMaxSymbols(int v) {
            if (v < 0) {
                throw new IllegalArgumentException("volume-selection.max-symbols must be >= 0, got " + v);
            }
            this.maxSymbols = v;
        }
        public double getKeepPercent() { return keepPercent; }
        public void setKeepPercent(double v) {
            if (!(v > 0 && v <= 100)) {
                throw new IllegalArgumentException("volume-selection.keep-percent must be in (0, 100], got " + v);
            }
            this.keepPercent = v;
        }
        public double getMinPriceRangePercent() { return minPriceRangePercent; }
        public void setMinPriceRangePercent(double v) { this.minPriceRangePercent = v; }
        public List<String> getExcludeTags() { return excludeTags; }
        public void setExcludeTags(List<String> v) { this.excludeTags = v == null ? new ArrayList<>() : v; }
        public List<String> getExcludeAssets() { return excludeAssets; }
        public void setExcludeAssets(List<String> v) { this.excludeAssets = v == null ? new ArrayList<>() : v; }
        public Duration getRefreshInterval() { return refreshInterval; }
        public void setRefreshInterval(Duration v) { this.refreshInterval = v; }
        public Duration getRetryInterval() { return retryInterval; }
        public void setRetryInterval(Duration v) { this.retryInterval = v; }

        /** The window in days, for turning its total volume into a daily average. */
        public double windowDays() {
            return window.toMillis() / (double) Duration.ofDays(1).toMillis();
        }
    }
}
