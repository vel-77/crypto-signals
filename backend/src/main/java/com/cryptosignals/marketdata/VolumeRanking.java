package com.cryptosignals.marketdata;

import com.cryptosignals.config.MarketProperties;
import com.cryptosignals.exchange.SymbolActivity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Chooses the coins to watch, on top of the fixed symbols, from the exchange's trading activity.
 *
 * <p>The rule, in order:
 * <ol>
 *   <li>Fixed symbols are watched anyway and are not ranked; they do count towards the maximum.</li>
 *   <li>Coins the exchange tags with an excluded tag (stablecoin, tokenized stock, commodity),
 *       excluded base assets (fiat, wrapped copies), and coins whose price barely moved over the
 *       window are skipped.</li>
 *   <li>A coin's volume is its average traded value per day over the window.</li>
 *   <li>A new coin needs at least the minimum volume. A coin already watched needs only
 *       {@code keep-percent} of it, and when the list is full it is only displaced by a coin with
 *       more than {@code 100 / keep-percent} times its volume. Both come from ranking a watched
 *       coin as if its volume were {@code volume * 100 / keep-percent}.</li>
 *   <li>The highest ranked coins fill the places left by the fixed symbols.</li>
 * </ol>
 *
 * <p>Pure: the same activity and the same current list always give the same answer.
 */
public final class VolumeRanking {

    /** One chosen coin and its average traded value per day. */
    public record Ranked(String symbol, double dailyVolume) {
    }

    private VolumeRanking() {
    }

    /**
     * @param activity     every symbol the exchange trades against the quote asset
     * @param fixedSymbols always watched; never part of the answer
     * @param watched      the coins chosen last time, which get the keep-percent allowance
     * @return the chosen coins, highest volume first
     */
    public static List<Ranked> choose(Collection<SymbolActivity> activity, Collection<String> fixedSymbols,
                                      Set<String> watched, MarketProperties.VolumeSelection rules) {
        Set<String> fixed = upper(fixedSymbols);
        Set<String> excludedAssets = upper(rules.getExcludeAssets());
        Set<String> excludedTags = lower(rules.getExcludeTags());
        double days = rules.windowDays();
        double keepFactor = 100.0 / rules.getKeepPercent();

        Map<String, SymbolActivity> bySymbol = new LinkedHashMap<>();
        for (SymbolActivity a : activity) {
            bySymbol.putIfAbsent(a.symbol().toUpperCase(Locale.ROOT), a);
        }

        record Scored(String symbol, double dailyVolume, double score) {
        }
        List<Scored> qualifying = new ArrayList<>();
        for (Map.Entry<String, SymbolActivity> entry : bySymbol.entrySet()) {
            String symbol = entry.getKey();
            SymbolActivity a = entry.getValue();
            if (fixed.contains(symbol)
                    || excludedAssets.contains(a.baseAsset().toUpperCase(Locale.ROOT))
                    || a.tags().stream().anyMatch(tag -> excludedTags.contains(tag.toLowerCase(Locale.ROOT)))
                    || a.priceRangePercent() < rules.getMinPriceRangePercent()) {
                continue;
            }
            double daily = a.quoteVolume() / days;
            double score = watched.contains(symbol) ? daily * keepFactor : daily;
            if (score >= rules.getMinDailyVolume()) {
                qualifying.add(new Scored(symbol, daily, score));
            }
        }

        qualifying.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(Scored::symbol));
        int places = rules.getMaxSymbols() == 0
                ? Integer.MAX_VALUE
                : Math.max(0, rules.getMaxSymbols() - fixed.size());

        List<Ranked> chosen = new ArrayList<>();
        for (Scored s : qualifying.subList(0, Math.min(places, qualifying.size()))) {
            chosen.add(new Ranked(s.symbol(), s.dailyVolume()));
        }
        chosen.sort(Comparator.comparingDouble(Ranked::dailyVolume).reversed()
                .thenComparing(Ranked::symbol));
        return chosen;
    }

    private static Set<String> upper(Collection<String> values) {
        Set<String> out = new HashSet<>();
        if (values != null) {
            values.forEach(v -> out.add(v.trim().toUpperCase(Locale.ROOT)));
        }
        return out;
    }

    private static Set<String> lower(Collection<String> values) {
        Set<String> out = new HashSet<>();
        if (values != null) {
            values.forEach(v -> out.add(v.trim().toLowerCase(Locale.ROOT)));
        }
        return out;
    }
}
