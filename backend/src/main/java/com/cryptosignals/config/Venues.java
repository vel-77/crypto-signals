package com.cryptosignals.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The markets the application runs, in configured order. The first is the default: what the API
 * answers for when no market is named, and what the web interface opens on.
 */
public final class Venues {

    private final List<Venue> all;
    private final Map<String, Venue> byName;

    public Venues(List<Venue> venues) {
        if (venues == null || venues.isEmpty()) {
            throw new IllegalArgumentException("At least one market must be configured");
        }
        Map<String, Venue> map = new LinkedHashMap<>();
        for (Venue venue : venues) {
            if (map.putIfAbsent(key(venue.name()), venue) != null) {
                throw new IllegalArgumentException("Market " + venue.name() + " is configured twice");
            }
        }
        this.all = List.copyOf(new ArrayList<>(map.values()));
        this.byName = Map.copyOf(map);
    }

    public List<Venue> all() {
        return all;
    }

    public Venue primary() {
        return all.get(0);
    }

    public Optional<Venue> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(key(name)));
    }

    /**
     * The market a request names; none named means the default.
     *
     * @throws IllegalArgumentException for a market that is not running, which the API answers
     *                                  with 400
     */
    public Venue resolve(String name) {
        if (name == null || name.isBlank()) {
            return primary();
        }
        return find(name).orElseThrow(() -> new IllegalArgumentException(
                "Unknown exchange '" + name.trim() + "'. Running: " + names()));
    }

    /** The label of a running market, or the name itself for one that is not running. */
    public String labelOf(String name) {
        return find(name).map(Venue::label).orElse(name);
    }

    public String names() {
        return all.stream().map(Venue::name).collect(Collectors.joining(", "));
    }

    private static String key(String name) {
        return name.trim().toUpperCase(Locale.ROOT);
    }
}
