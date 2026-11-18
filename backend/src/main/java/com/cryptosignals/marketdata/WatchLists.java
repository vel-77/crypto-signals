package com.cryptosignals.marketdata;

import com.cryptosignals.config.MarketProperties;
import com.cryptosignals.config.Venue;
import com.cryptosignals.config.Venues;
import com.cryptosignals.exchange.SymbolActivity;
import com.cryptosignals.persistence.jdbc.WatchedSymbolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * One {@link WatchList} per running market, in configured order.
 *
 * <p>The lists are chosen together: {@code min-symbols} and {@code max-symbols} count the coins of
 * all markets, so one market's list depends on what the others trade (see
 * {@link VolumeRanking#chooseAcross}).
 */
@Service
public class WatchLists {

    private static final Logger log = LoggerFactory.getLogger(WatchLists.class);

    private final MarketProperties properties;
    private final Venues venues;
    private final Map<String, WatchList> byVenue = new LinkedHashMap<>();
    private boolean initialised;

    public WatchLists(MarketProperties properties, Venues venues, WatchedSymbolRepository stored) {
        this.properties = properties;
        this.venues = venues;
        for (Venue venue : venues.all()) {
            byVenue.put(venue.name(), new WatchList(properties, venue, stored));
        }
    }

    public WatchList of(Venue venue) {
        return byVenue.get(venue.name());
    }

    /** The list of the market a request names; none named means the default market. */
    public WatchList resolve(String exchange) {
        return of(venues.resolve(exchange));
    }

    public List<WatchList> all() {
        return List.copyOf(byVenue.values());
    }

    /** Loads every market's stored list and refreshes them together from the exchanges. */
    public synchronized void initialise() {
        byVenue.values().forEach(WatchList::load);
        initialised = true;
        refresh();
    }

    /**
     * {@link #initialise()} unless done already. Each market's start-up calls it; the first one
     * does the work and the others wait for it.
     */
    public synchronized void initialiseOnce() {
        if (!initialised) {
            initialise();
        }
    }

    /** Whether any market's list is due a refresh. */
    public boolean isDue(Instant now) {
        return byVenue.values().stream().anyMatch(list -> list.isDue(now));
    }

    /**
     * Works every market's list out again, together, and stores them.
     *
     * @return per list, what changed; empty for a market whose exchange could not be asked, whose
     *         list is then kept and retried after the retry interval
     */
    public synchronized Map<WatchList, Optional<WatchList.Change>> refresh() {
        return refreshTogether(all(), properties.getVolumeSelection());
    }

    /**
     * Refreshes the given lists together. A market whose exchange cannot be asked keeps its list,
     * which still counts towards the others' minimum and maximum. The answer is in the given order.
     */
    static Map<WatchList, Optional<WatchList.Change>> refreshTogether(List<WatchList> lists,
                                                                      MarketProperties.VolumeSelection rules) {
        Map<WatchList, Optional<WatchList.Change>> changes = new LinkedHashMap<>();
        if (!rules.isEnabled()) {
            lists.forEach(list -> changes.put(list, Optional.empty()));
            return changes;
        }
        Instant now = Instant.now();
        Map<WatchList, List<SymbolActivity>> activity = new LinkedHashMap<>();
        List<VolumeRanking.Market> markets = new ArrayList<>();
        for (WatchList list : lists) {
            try {
                List<SymbolActivity> traded = list.tradingActivity();
                activity.put(list, traded);
                markets.add(list.rankingInput(traded));
            } catch (RuntimeException ex) {
                list.failed(ex, now);
                markets.add(list.asItIs());
            }
        }

        Map<String, List<VolumeRanking.Ranked>> chosen = VolumeRanking.chooseAcross(markets, rules);
        for (WatchList list : lists) {
            Optional<WatchList.Change> change = Optional.empty();
            if (activity.containsKey(list)) {
                try {
                    change = Optional.of(list.update(chosen.get(list.venue().name()), activity.get(list), now));
                } catch (RuntimeException ex) {
                    list.failed(ex, now);
                }
            }
            changes.put(list, change);
        }

        if (lists.size() > 1 && (rules.getMinSymbols() > 0 || rules.getMaxSymbols() > 0)) {
            int total = lists.stream().mapToInt(list -> list.symbols().size()).sum();
            log.info("Watch lists: {} coin(s) across all markets ({}{}): {}", total,
                    rules.getMinSymbols() > 0 ? "at least " + rules.getMinSymbols() : "",
                    rules.getMaxSymbols() > 0
                            ? (rules.getMinSymbols() > 0 ? ", " : "") + "at most " + rules.getMaxSymbols()
                            : "",
                    lists.stream().map(list -> list.venue().label() + " " + list.symbols().size())
                            .collect(Collectors.joining(", ")));
        }
        return changes;
    }
}
