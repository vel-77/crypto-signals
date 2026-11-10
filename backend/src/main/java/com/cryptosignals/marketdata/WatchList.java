package com.cryptosignals.marketdata;

import com.cryptosignals.config.ExchangeProperties;
import com.cryptosignals.config.MarketProperties;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.exchange.ExchangeClient;
import com.cryptosignals.exchange.SymbolActivity;
import com.cryptosignals.persistence.jdbc.WatchedSymbolRepository;
import com.cryptosignals.persistence.jdbc.WatchedSymbolRepository.WatchedSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 * Which symbols are watched: the fixed ones from the configuration, plus — with volume selection
 * on — the most traded other coins (see {@link VolumeRanking}).
 *
 * <p>The chosen coins are stored after every successful refresh. On startup the stored list is
 * loaded first and then refreshed from the exchange; if the exchange cannot be asked, the stored
 * list is used and the refresh is retried soon after. A failed refresh never changes the list.
 *
 * <p>This only decides the list. Starting and stopping markets to match it is
 * {@link MarketDataBootstrap}'s job.
 */
@Service
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

    public WatchList(MarketProperties properties, ExchangeClient exchange,
                     WatchedSymbolRepository stored, ExchangeProperties exchangeProperties) {
        this.properties = properties;
        this.exchange = exchange;
        this.stored = stored;
        this.exchangeName = exchangeProperties.getName();
        this.fixed = properties.fixedSymbols();
    }

    /** Loads the stored list and refreshes it from the exchange. Called once, at startup. */
    public synchronized void initialise() {
        fixed = properties.fixedSymbols();
        if (!properties.getVolumeSelection().isEnabled()) {
            chosen = List.of();
            source = Source.FIXED;
            settled = true;
            log.info("Watch list: {} fixed symbol(s); volume selection is off", fixed.size());
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
            log.info("Watch list: loaded {} coin(s) chosen by volume earlier", previous.size());
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
            List<VolumeRanking.Ranked> ranked = VolumeRanking.choose(activity, fixed, current, rules);

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

            log.info("Watch list: {} symbol(s), {} fixed and {} chosen by volume (>= {} {}/day over {}, "
                            + "at most {}); added {}, removed {}",
                    fixed.size() + next.size(), fixed.size(), next.size(),
                    String.format(Locale.ROOT, "%,.0f", rules.getMinDailyVolume()), rules.getQuoteAsset(),
                    days(rules), rules.getMaxSymbols() == 0 ? "no limit" : rules.getMaxSymbols(),
                    added.isEmpty() ? "none" : added, removed.isEmpty() ? "none" : removed);
            return Optional.of(new Change(added, removed));
        } catch (RuntimeException ex) {
            lastError = ex.toString();
            nextAttemptAt = now.plus(rules.getRetryInterval());
            log.warn("Could not refresh the watch list ({}); keeping the current {} symbol(s), retrying at {}",
                    ex.toString(), fixed.size() + chosen.size(), nextAttemptAt);
            return Optional.empty();
        }
    }

    /** Whether a refresh is due: the refresh interval has passed, or a failed one is to be retried. */
    public synchronized boolean isDue(Instant now) {
        return properties.getVolumeSelection().isEnabled()
                && nextAttemptAt != null && !now.isBefore(nextAttemptAt);
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
