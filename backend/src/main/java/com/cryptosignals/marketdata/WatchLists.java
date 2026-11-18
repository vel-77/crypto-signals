package com.cryptosignals.marketdata;

import com.cryptosignals.config.MarketProperties;
import com.cryptosignals.config.Venue;
import com.cryptosignals.config.Venues;
import com.cryptosignals.persistence.jdbc.WatchedSymbolRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One {@link WatchList} per running market, in configured order. */
@Service
public class WatchLists {

    private final Venues venues;
    private final Map<String, WatchList> byVenue = new LinkedHashMap<>();

    public WatchLists(MarketProperties properties, Venues venues, WatchedSymbolRepository stored) {
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
}
