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
 * Chooses the coins to watch, on top of the fixed symbols, from the exchange's trading activity:
 * on one market, or on several at once ({@link #chooseAcross}).
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
 *   <li>If fewer than {@code min-symbols} coins are watched that way, fixed ones included, the
 *       next highest ranked coins make up the number, below the minimum volume. The keep-percent
 *       allowance works the same there: a watched coin only gives way to one trading more than
 *       {@code 100 / keep-percent} times as much.</li>
 *   <li>{@code max-symbols}, when set, caps the total either way.</li>
 * </ol>
 *
 * <p>With several markets, {@code min-symbols} and {@code max-symbols} count the coins of all of
 * them together, and each market's fixed symbols count once per market. Each market keeps the
 * coins clearing its own minimum. Coins below it compare by how close they come to their own
 * market's minimum: a futures coin at 40% of a 300M minimum ranks with a spot coin at 40% of a
 * 50M one, whatever their volumes.
 *
 * <p>Pure: the same activity and the same current list always give the same answer.
 */
public final class VolumeRanking {

    /** One chosen coin and its average traded value per day. */
    public record Ranked(String symbol, double dailyVolume) {
    }

    /**
     * One market's part in a choice across markets.
     *
     * @param name           tells the markets apart in the answer
     * @param activity       every symbol the market trades against the quote asset
     * @param fixedSymbols   always watched; counted, never ranked
     * @param watched        the coins chosen last time, which get the keep-percent allowance
     * @param minDailyVolume what a coin needs to join on this market
     */
    public record Market(String name, Collection<SymbolActivity> activity, Collection<String> fixedSymbols,
                         Set<String> watched, double minDailyVolume) {
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
        return choose(activity, fixedSymbols, watched, rules, rules.getMinDailyVolume());
    }

    /**
     * The same, with the market's own minimum daily volume in place of the rules' default.
     */
    public static List<Ranked> choose(Collection<SymbolActivity> activity, Collection<String> fixedSymbols,
                                      Set<String> watched, MarketProperties.VolumeSelection rules,
                                      double minDailyVolume) {
        Market market = new Market("", activity, fixedSymbols, watched, minDailyVolume);
        return chooseAcross(List.of(market), rules).get("");
    }

    /**
     * Chooses on several markets at once, with {@code min-symbols} and {@code max-symbols}
     * counting all of them together.
     *
     * @return each market's chosen coins, by market name in the given order, highest volume first
     */
    public static Map<String, List<Ranked>> chooseAcross(List<Market> markets, MarketProperties.VolumeSelection rules) {
        Set<String> excludedAssets = upper(rules.getExcludeAssets());
        Set<String> excludedTags = lower(rules.getExcludeTags());
        double days = rules.windowDays();
        double keepFactor = 100.0 / rules.getKeepPercent();

        // nearness: the score as a share of the market's own minimum, which ranks coins of
        // markets with different minimums against each other.
        record Scored(String market, String symbol, double dailyVolume, double score, boolean clears,
                      double nearness) {
        }
        List<Scored> eligible = new ArrayList<>();
        int fixedCount = 0;
        for (Market market : markets) {
            Set<String> fixed = upper(market.fixedSymbols());
            fixedCount += fixed.size();
            double minDailyVolume = market.minDailyVolume();

            Map<String, SymbolActivity> bySymbol = new LinkedHashMap<>();
            for (SymbolActivity a : market.activity()) {
                bySymbol.putIfAbsent(a.symbol().toUpperCase(Locale.ROOT), a);
            }
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
                double score = market.watched().contains(symbol) ? daily * keepFactor : daily;
                double nearness = minDailyVolume > 0 ? score / minDailyVolume : Double.POSITIVE_INFINITY;
                eligible.add(new Scored(market.name(), symbol, daily, score, score >= minDailyVolume, nearness));
            }
        }

        // The coins clearing their market's minimum come first, then the nearest to it.
        eligible.sort(Comparator.comparing(Scored::clears).reversed()
                .thenComparing(Comparator.comparingDouble(Scored::nearness).reversed())
                .thenComparing(Comparator.comparingDouble(Scored::score).reversed())
                .thenComparing(Scored::market)
                .thenComparing(Scored::symbol));
        int clearing = (int) eligible.stream().filter(Scored::clears).count();
        int wanted = Math.max(clearing, rules.getMinSymbols() - fixedCount);
        int places = rules.getMaxSymbols() == 0
                ? Integer.MAX_VALUE
                : Math.max(0, rules.getMaxSymbols() - fixedCount);

        Map<String, List<Ranked>> chosen = new LinkedHashMap<>();
        markets.forEach(m -> chosen.put(m.name(), new ArrayList<>()));
        for (Scored s : eligible.subList(0, Math.min(Math.min(wanted, places), eligible.size()))) {
            chosen.get(s.market()).add(new Ranked(s.symbol(), s.dailyVolume()));
        }
        chosen.values().forEach(list -> list.sort(Comparator.comparingDouble(Ranked::dailyVolume).reversed()
                .thenComparing(Ranked::symbol)));
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
