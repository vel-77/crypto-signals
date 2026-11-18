package com.cryptosignals.marketdata;

import com.cryptosignals.config.MarketProperties;
import com.cryptosignals.config.Venue;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.exchange.ExchangeClient;
import com.cryptosignals.exchange.SymbolActivity;
import com.cryptosignals.persistence.jdbc.WatchedSymbolRepository;
import com.cryptosignals.persistence.jdbc.WatchedSymbolRepository.WatchedSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which symbols are watched on one market: the fixed ones from the configuration, plus — with
 * volume selection on — the most traded other coins (see {@link VolumeRanking}), with the
 * market's own minimum volume. Each market has its own list ({@link WatchLists}).
 *
 * <p>A fixed symbol is looked up under the market's own spelling, so one list serves spot and
 * futures: {@code 1000SHIBUSDT} on futures is {@code SHIBUSDT} on spot.
 *
 * <p>The chosen coins are stored after every successful refresh. On startup the stored list is
 * loaded first and then refreshed from the exchange; if the exchange cannot be asked, the stored
 * list is used and the refresh is retried soon after. A failed refresh never changes the list.
 *
 * <p>This only decides the list. Starting and stopping markets to match it is
 * {@link MarketDataBootstrap}'s job.
 */
public class WatchList {

    private static final Logger log = LoggerFactory.getLogger(WatchList.class);

    /** Where the current list came from. */
    public enum Source {
        /** Volume selection is off: only the fixed symbols. */
        FIXED,
        /** Loaded from the database; the exchange has not been asked yet. */
        STORED,
        /** Worked out from the exchange's volumes. */
        EXCHANGE
    }

    /** One watched symbol. {@code dailyVolume} is null when not known yet. */
    public record Entry(String symbol, boolean fixed, Double dailyVolume, Instant selectedAt) {
    }

    /** What a refresh changed among the chosen coins. */
    public record Change(List<String> added, List<String> removed) {
        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty();
        }
    }

    private final MarketProperties properties;
    private final Venue venue;
    private final ExchangeClient exchange;
    private final WatchedSymbolRepository stored;
    private final String exchangeName;

    private List<String> fixed = List.of();
    private List<WatchedSymbol> chosen = List.of();
    /** Latest known daily volume of every watched symbol, fixed ones included. */
    private Map<String, Double> volumes = Map.of();
    private Source source = Source.FIXED;
    private boolean settled;
    private Instant refreshedAt;
    private Instant lastAttemptAt;
    private Instant nextAttemptAt;
    private String lastError;

    public WatchList(MarketProperties properties, Venue venue, WatchedSymbolRepository stored) {
        this.properties = properties;
        this.venue = venue;
        this.exchange = venue.client();
        this.stored = stored;
        this.exchangeName = venue.name();
        this.fixed = properties.fixedSymbols();
    }

    /** Loads the stored list and refreshes it from the exchange. Called once, at startup. */
    public synchronized void initialise() {
        fixed = listedFixedSymbols();
        if (!properties.getVolumeSelection().isEnabled()) {
            chosen = List.of();
            source = Source.FIXED;
            settled = true;
            log.info("Watch list on {}: {} fixed symbol(s); volume selection is off", venue.label(), fixed.size());
            return;
        }
        List<WatchedSymbol> previous = stored.findAll(exchangeName).stream()
                .filter(s -> !fixed.contains(s.symbol()))
                .toList();
        chosen = previous;
        source = Source.STORED;
        // An empty table is a first run, not a decision that nothing qualifies: until the exchange
        // has answered, nothing is retired for being off the list.
        settled = !previous.isEmpty();
        volumes = volumesOf(previous);
        if (!previous.isEmpty()) {
            log.info("Watch list on {}: loaded {} coin(s) chosen by volume earlier", venue.label(), previous.size());
        }
        refresh();
    }

    /**
     * Works the chosen coins out again from the exchange's volumes and stores them.
     *
     * @return what changed, or empty if the exchange could not be asked (the list is then kept
     *         as it was and the refresh is retried after the retry interval)
     */
    public synchronized Optional<Change> refresh() {
        MarketProperties.VolumeSelection rules = properties.getVolumeSelection();
        if (!rules.isEnabled()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        lastAttemptAt = now;
        try {
            List<SymbolActivity> activity = exchange.tradingActivity(rules.getQuoteAsset(), rules.getWindow());
            Set<String> current = symbolsOf(chosen);
            List<VolumeRanking.Ranked> ranked = VolumeRanking.choose(activity, fixed, current, rules,
                    minDailyVolume());

            Map<String, Instant> joined = new HashMap<>();
            chosen.forEach(s -> joined.put(s.symbol(), s.selectedAt()));
            List<WatchedSymbol> next = ranked.stream()
                    .map(r -> new WatchedSymbol(r.symbol(), r.dailyVolume(),
                            joined.getOrDefault(r.symbol(), now), now))
                    .toList();
            stored.replaceAll(exchangeName, next);

            Set<String> nextSymbols = symbolsOf(next);
            List<String> added = nextSymbols.stream().filter(s -> !current.contains(s)).toList();
            List<String> removed = current.stream().filter(s -> !nextSymbols.contains(s)).toList();

            Map<String, Double> known = new HashMap<>();
            double days = rules.windowDays();
            for (SymbolActivity a : activity) {
                known.put(a.symbol().toUpperCase(Locale.ROOT), a.quoteVolume() / days);
            }
            chosen = next;
            volumes = known;
            source = Source.EXCHANGE;
            settled = true;
            refreshedAt = now;
            lastError = null;
            nextAttemptAt = now.plus(rules.getRefreshInterval());

            log.info("Watch list on {}: {} symbol(s), {} fixed and {} chosen by volume (>= {} {}/day over {}{}); "
                            + "added {}, removed {}",
                    venue.label(), fixed.size() + next.size(), fixed.size(), next.size(),
                    String.format(Locale.ROOT, "%,.0f", minDailyVolume()), rules.getQuoteAsset(),
                    days(rules),
                    (rules.getMinSymbols() == 0 ? "" : ", at least " + rules.getMinSymbols())
                            + (rules.getMaxSymbols() == 0 ? "" : ", at most " + rules.getMaxSymbols()),
                    added.isEmpty() ? "none" : added, removed.isEmpty() ? "none" : removed);
            return Optional.of(new Change(added, removed));
        } catch (RuntimeException ex) {
            lastError = ex.toString();
            nextAttemptAt = now.plus(rules.getRetryInterval());
            log.warn("Could not refresh the watch list on {} ({}); keeping the current {} symbol(s), retrying at {}",
                    venue.label(), ex.toString(), fixed.size() + chosen.size(), nextAttemptAt);
            return Optional.empty();
        }
    }

    /** Whether a refresh is due: the refresh interval has passed, or a failed one is to be retried. */
    public synchronized boolean isDue(Instant now) {
        return properties.getVolumeSelection().isEnabled()
                && nextAttemptAt != null && !now.isBefore(nextAttemptAt);
    }

    /**
     * The fixed symbols as this market spells them. One the market does not list under any
     * spelling, or that cannot be looked up right now, keeps its configured spelling; starting its
     * markets then skips it with a warning.
     */
    private List<String> listedFixedSymbols() {
        Set<String> listed = new LinkedHashSet<>();
        for (String symbol : properties.fixedSymbols()) {
            String spelling = symbol;
            try {
                spelling = exchange.listedSymbol(symbol).orElse(symbol);
            } catch (RuntimeException ex) {
                log.warn("Could not look up {} on {} ({}); using it as configured", symbol, venue.label(), ex.toString());
            }
            if (!spelling.equals(symbol)) {
                log.info("Watch list on {}: {} trades as {}", venue.label(), symbol, spelling);
            }
            listed.add(spelling);
        }
        return List.copyOf(listed);
    }

    public Venue venue() {
        return venue;
    }

    /** What a coin needs to join this market's list, per day in the quote asset. */
    public double minDailyVolume() {
        return properties.getVolumeSelection().minDailyVolumeFor(exchangeName);
    }

    /** The fixed symbols, as this market spells them. */
    public synchronized List<String> fixedSymbols() {
        return fixed;
    }

    /** Every watched symbol: the fixed ones in configured order, then the chosen ones by volume. */
    public synchronized List<String> symbols() {
        List<String> all = new ArrayList<>(fixed);
        chosen.forEach(s -> all.add(s.symbol()));
        return all;
    }

    public synchronized List<MarketKey> marketKeys() {
        return properties.toMarketKeys(symbols());
    }

    public synchronized List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        for (String symbol : fixed) {
            entries.add(new Entry(symbol, true, volumes.get(symbol), null));
        }
        for (WatchedSymbol s : chosen) {
            entries.add(new Entry(s.symbol(), false, volumes.getOrDefault(s.symbol(), s.dailyVolume()),
                    s.selectedAt()));
        }
        return entries;
    }

    /**
     * Whether the list is known to be the intended one, so markets off it can be retired. False
     * only on a first run with volume selection on, until the exchange has answered once.
     */
    public synchronized boolean isSettled() {
        return settled;
    }

    public synchronized Source source() {
        return source;
    }

    public synchronized Instant refreshedAt() {
        return refreshedAt;
    }

    public synchronized Instant lastAttemptAt() {
        return lastAttemptAt;
    }

    public synchronized Instant nextAttemptAt() {
        return properties.getVolumeSelection().isEnabled() ? nextAttemptAt : null;
    }

    public synchronized String lastError() {
        return lastError;
    }

    private static String days(MarketProperties.VolumeSelection rules) {
        double days = rules.windowDays();
        return days == Math.rint(days) ? (long) days + "d" : rules.getWindow().toString();
    }

    private static Set<String> symbolsOf(List<WatchedSymbol> list) {
        Set<String> out = new LinkedHashSet<>();
        list.forEach(s -> out.add(s.symbol()));
        return out;
    }

    private static Map<String, Double> volumesOf(List<WatchedSymbol> list) {
        Map<String, Double> out = new HashMap<>();
        list.forEach(s -> out.put(s.symbol(), s.dailyVolume()));
        return out;
    }
}
