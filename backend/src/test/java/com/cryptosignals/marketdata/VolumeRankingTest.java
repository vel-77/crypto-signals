package com.cryptosignals.marketdata;

import com.cryptosignals.config.MarketProperties;
import com.cryptosignals.exchange.SymbolActivity;
import com.cryptosignals.marketdata.VolumeRanking.Ranked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class VolumeRankingTest {

    private static final double M = 1_000_000;

    /** Defaults, except a 1-day window so a coin's volume is its daily volume. */
    private static MarketProperties.VolumeSelection rules() {
        MarketProperties.VolumeSelection rules = new MarketProperties.VolumeSelection();
        rules.setEnabled(true);
        rules.setWindow(Duration.ofDays(1));
        return rules;
    }

    /** A coin that moved 10% over the window and traded {@code dailyMillions} a day. */
    private static SymbolActivity coin(String base, double dailyMillions, String... tags) {
        return new SymbolActivity(base + "USDT", base, dailyMillions * M, 110, 100, Set.of(tags));
    }

    private static List<String> symbols(List<Ranked> ranked) {
        return ranked.stream().map(Ranked::symbol).toList();
    }

    private static List<String> choose(List<SymbolActivity> activity, List<String> fixed, Set<String> watched,
                                       MarketProperties.VolumeSelection rules) {
        return symbols(VolumeRanking.choose(activity, fixed, watched, rules));
    }

    @Test
    @DisplayName("coins at or above the minimum daily volume are chosen, highest first")
    void floor() {
        List<SymbolActivity> activity = List.of(coin("AAA", 4.99), coin("BBB", 5), coin("CCC", 80), coin("DDD", 12));

        assertThat(choose(activity, List.of(), Set.of(), rules())).containsExactly("CCCUSDT", "DDDUSDT", "BBBUSDT");
    }

    @Test
    @DisplayName("volume is averaged per day over the window")
    void dailyAverage() {
        MarketProperties.VolumeSelection rules = rules();
        rules.setWindow(Duration.ofDays(7));
        // 7-day totals: 34M is under 5M a day, 35M is exactly 5M a day.
        List<SymbolActivity> activity = List.of(coin("AAA", 34), coin("BBB", 35));

        List<Ranked> chosen = VolumeRanking.choose(activity, List.of(), Set.of(), rules);

        assertThat(symbols(chosen)).containsExactly("BBBUSDT");
        assertThat(chosen.get(0).dailyVolume()).isCloseTo(5 * M, within(1e-6));
    }

    @Test
    @DisplayName("the maximum counts the fixed symbols, which are never ranked themselves")
    void maximumIncludesFixed() {
        MarketProperties.VolumeSelection rules = rules();
        rules.setMaxSymbols(4);
        List<SymbolActivity> activity = List.of(coin("BTC", 900), coin("AAA", 50), coin("BBB", 40),
                coin("CCC", 30), coin("DDD", 20));

        assertThat(choose(activity, List.of("BTCUSDT", "ETHUSDT"), Set.of(), rules))
                .containsExactly("AAAUSDT", "BBBUSDT");
    }

    @Test
    @DisplayName("a maximum of 0 means no limit; fixed symbols at or over the maximum leave no room")
    void maximumEdges() {
        List<SymbolActivity> activity = List.of(coin("AAA", 50), coin("BBB", 40), coin("CCC", 30));
        MarketProperties.VolumeSelection unlimited = rules();
        unlimited.setMaxSymbols(0);
        MarketProperties.VolumeSelection two = rules();
        two.setMaxSymbols(2);

        assertThat(choose(activity, List.of(), Set.of(), unlimited)).hasSize(3);
        assertThat(choose(activity, List.of("XUSDT", "YUSDT", "ZUSDT"), Set.of(), two)).isEmpty();
    }

    @Test
    @DisplayName("stablecoins, tokenized stocks and commodities are skipped by their exchange tags")
    void excludedTags() {
        List<SymbolActivity> activity = List.of(coin("USDC", 900, "stablecoin"),
                coin("NVDAB", 60, "bStocks"), coin("PAXG", 40, "tCommodities"),
                coin("SOL", 30, "Layer1_Layer2", "pos"), coin("PEPE", 20, "Meme"));

        assertThat(choose(activity, List.of(), Set.of(), rules())).containsExactly("SOLUSDT", "PEPEUSDT");
    }

    @Test
    @DisplayName("tags match whatever their case")
    void tagsIgnoreCase() {
        MarketProperties.VolumeSelection rules = rules();
        rules.setExcludeTags(List.of("BSTOCKS"));

        assertThat(choose(List.of(coin("TSLAB", 60, "bStocks")), List.of(), Set.of(), rules)).isEmpty();
    }

    @Test
    @DisplayName("excluded base assets are skipped: fiat and wrapped copies")
    void excludedAssets() {
        List<SymbolActivity> activity = List.of(coin("EUR", 90), coin("WBTC", 80), coin("WBETH", 70),
                coin("ETH", 60));

        assertThat(choose(activity, List.of(), Set.of(), rules())).containsExactly("ETHUSDT");
    }

    @Test
    @DisplayName("a coin whose price barely moved is skipped, even untagged: a stablecoin")
    void flatPrice() {
        SymbolActivity pegged = new SymbolActivity("NEWUSDUSDT", "NEWUSD", 500 * M, 1.004, 0.998, Set.of());
        SymbolActivity moving = new SymbolActivity("ZECUSDT", "ZEC", 50 * M, 1230, 1000, Set.of());

        assertThat(choose(List.of(pegged, moving), List.of(), Set.of(), rules())).containsExactly("ZECUSDT");
    }

    @Test
    @DisplayName("a watched coin stays until it falls below keep-percent of the minimum")
    void keepsWatchedCoinsNearTheFloor() {
        List<SymbolActivity> activity = List.of(coin("AAA", 3.6), coin("BBB", 3.4), coin("CCC", 3.6));
        Set<String> watched = Set.of("AAAUSDT", "BBBUSDT");

        // 70% of 5M is 3.5M: AAA stays, BBB goes, and CCC at the same volume as AAA does not join.
        assertThat(choose(activity, List.of(), watched, rules())).containsExactly("AAAUSDT");
    }

    @Test
    @DisplayName("on a full list a watched coin is only displaced by one with clearly more volume")
    void keepsWatchedCoinsWhenFull() {
        MarketProperties.VolumeSelection rules = rules();
        rules.setMaxSymbols(1);
        Set<String> watched = Set.of("AAAUSDT");

        // 100 / 70 = 1.43: a newcomer needs more than 1.43 times the watched coin's volume.
        assertThat(choose(List.of(coin("AAA", 10), coin("BBB", 14)), List.of(), watched, rules))
                .containsExactly("AAAUSDT");
        assertThat(choose(List.of(coin("AAA", 10), coin("BBB", 14.5)), List.of(), watched, rules))
                .containsExactly("BBBUSDT");
    }

    @Test
    @DisplayName("too few coins over the minimum: the next most traded make up min-symbols, fixed ones included")
    void fillsUpToMinimum() {
        MarketProperties.VolumeSelection rules = rules();   // 5M a day
        rules.setMinSymbols(4);
        List<SymbolActivity> activity = List.of(coin("BTC", 900), coin("AAA", 50), coin("BBB", 4),
                coin("CCC", 3), coin("DDD", 2), coin("EEE", 1));

        assertThat(choose(activity, List.of("BTCUSDT"), Set.of(), rules))
                .containsExactly("AAAUSDT", "BBBUSDT", "CCCUSDT");
    }

    @Test
    @DisplayName("the minimum number never leaves out a coin over the minimum volume")
    void minimumIsAFloorOnly() {
        MarketProperties.VolumeSelection rules = rules();
        rules.setMinSymbols(2);
        List<SymbolActivity> activity = List.of(coin("AAA", 50), coin("BBB", 40), coin("CCC", 30), coin("DDD", 1));

        assertThat(choose(activity, List.of(), Set.of(), rules)).containsExactly("AAAUSDT", "BBBUSDT", "CCCUSDT");
    }

    @Test
    @DisplayName("filling up still skips stablecoins and the like, and stops at max-symbols")
    void fillingRespectsExclusionsAndMaximum() {
        MarketProperties.VolumeSelection rules = rules();
        rules.setMinSymbols(3);
        List<SymbolActivity> activity = List.of(coin("USDC", 4, "stablecoin"), coin("AAA", 3), coin("BBB", 2),
                coin("CCC", 1));

        assertThat(choose(activity, List.of(), Set.of(), rules)).containsExactly("AAAUSDT", "BBBUSDT", "CCCUSDT");
        rules.setMaxSymbols(2);
        assertThat(choose(activity, List.of(), Set.of(), rules)).containsExactly("AAAUSDT", "BBBUSDT");
    }

    @Test
    @DisplayName("among the coins filling up, a watched one only gives way to one with clearly more volume")
    void fillingKeepsWatchedCoins() {
        MarketProperties.VolumeSelection rules = rules();
        rules.setMinSymbols(1);
        Set<String> watched = Set.of("AAAUSDT");

        assertThat(choose(List.of(coin("AAA", 1), coin("BBB", 1.4)), List.of(), watched, rules))
                .containsExactly("AAAUSDT");
        assertThat(choose(List.of(coin("AAA", 1), coin("BBB", 1.5)), List.of(), watched, rules))
                .containsExactly("BBBUSDT");
    }

    @Test
    @DisplayName("ties are broken by symbol, so the same input always gives the same list")
    void deterministic() {
        MarketProperties.VolumeSelection rules = rules();
        rules.setMaxSymbols(2);
        List<SymbolActivity> activity = List.of(coin("CCC", 9), coin("AAA", 9), coin("BBB", 9));

        assertThat(choose(activity, List.of(), Set.of(), rules)).containsExactly("AAAUSDT", "BBBUSDT");
        assertThat(choose(activity.reversed(), List.of(), Set.of(), rules)).containsExactly("AAAUSDT", "BBBUSDT");
    }

    @Test
    @DisplayName("settings are checked when they are read")
    void validation() {
        MarketProperties.VolumeSelection rules = rules();

        assertThatThrownBy(() -> rules.setWindow(Duration.ofDays(8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.setKeepPercent(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.setKeepPercent(120)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.setMaxSymbols(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.setMinSymbols(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.setMinDailyVolume(-5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.setMinDailyVolumeByExchange("BINANCE=5"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MARKET:volume");
        assertThatThrownBy(() -> rules.setMinDailyVolumeByExchange("BINANCE:"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.setMinDailyVolumeByExchange("BINANCE:300.000.000"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.setMinDailyVolumeByExchange(":5"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("each market can have its own minimum; others use the default")
    void minimumPerMarket() {
        MarketProperties.VolumeSelection rules = rules();
        rules.setMinDailyVolumeByExchange(" BINANCE_FUTURES:300000000 , binance:50_000_000 ");

        assertThat(rules.minDailyVolumeFor("BINANCE_FUTURES")).isEqualTo(300 * M);
        assertThat(rules.minDailyVolumeFor("binance")).isEqualTo(50 * M);
        assertThat(rules.minDailyVolumeFor("BYBIT")).isEqualTo(5 * M);

        List<SymbolActivity> activity = List.of(coin("AAA", 400), coin("BBB", 120), coin("CCC", 30));
        assertThat(symbols(VolumeRanking.choose(activity, List.of(), Set.of(), rules,
                rules.minDailyVolumeFor("BINANCE_FUTURES")))).containsExactly("AAAUSDT");
        assertThat(symbols(VolumeRanking.choose(activity, List.of(), Set.of(), rules,
                rules.minDailyVolumeFor("BINANCE")))).containsExactly("AAAUSDT", "BBBUSDT");

        rules.setMinDailyVolumeByExchange("");
        assertThat(rules.minDailyVolumeFor("BINANCE_FUTURES")).isEqualTo(5 * M);
    }

    private static Map<String, List<String>> across(MarketProperties.VolumeSelection rules,
                                                    VolumeRanking.Market... markets) {
        Map<String, List<Ranked>> chosen = VolumeRanking.chooseAcross(List.of(markets), rules);
        java.util.LinkedHashMap<String, List<String>> out = new java.util.LinkedHashMap<>();
        chosen.forEach((market, ranked) -> out.put(market, symbols(ranked)));
        return out;
    }

    @Test
    @DisplayName("across markets, the fewest coins counts them together and fills up nearest each market's own minimum")
    void minimumAcrossMarkets() {
        MarketProperties.VolumeSelection rules = rules();
        rules.setMinSymbols(7);
        // AAA clears both minimums. Below them: BBB at 80% of futures', DDD at 70% and FFF at 40% of
        // spot's, CCC at 30% of futures', EEE at 20% of spot's.
        VolumeRanking.Market futures = new VolumeRanking.Market("FUTURES",
                List.of(coin("BTC", 900), coin("AAA", 400), coin("BBB", 240), coin("CCC", 90)),
                List.of("BTCUSDT"), Set.of(), 300 * M);
        VolumeRanking.Market spot = new VolumeRanking.Market("SPOT",
                List.of(coin("BTC", 150), coin("AAA", 60), coin("DDD", 35), coin("EEE", 10), coin("FFF", 20)),
                List.of("BTCUSDT"), Set.of(), 50 * M);

        // BTC twice and AAA twice make 4; BBB, DDD and FFF make 7, though CCC trades more than DDD.
        assertThat(across(rules, futures, spot)).containsExactly(
                Map.entry("FUTURES", List.of("AAAUSDT", "BBBUSDT")),
                Map.entry("SPOT", List.of("AAAUSDT", "DDDUSDT", "FFFUSDT")));

        // Without a minimum, each market takes only what clears its own.
        rules.setMinSymbols(0);
        assertThat(across(rules, futures, spot)).containsExactly(
                Map.entry("FUTURES", List.of("AAAUSDT")),
                Map.entry("SPOT", List.of("AAAUSDT")));
    }

    @Test
    @DisplayName("across markets, a watched coin keeps its allowance against coins of the other market")
    void allowanceAcrossMarkets() {
        MarketProperties.VolumeSelection rules = rules();
        rules.setMinSymbols(1);
        // One place: a new 80% coin on futures against a watched 60% one on spot, which counts as
        // 60 / 0.7 = 86%.
        VolumeRanking.Market futures = new VolumeRanking.Market("FUTURES",
                List.of(coin("BBB", 240)), List.of(), Set.of(), 300 * M);
        VolumeRanking.Market spot = new VolumeRanking.Market("SPOT",
                List.of(coin("DDD", 30)), List.of(), Set.of("DDDUSDT"), 50 * M);

        assertThat(across(rules, futures, spot)).containsExactly(
                Map.entry("FUTURES", List.of()),
                Map.entry("SPOT", List.of("DDDUSDT")));
    }

    @Test
    @DisplayName("across markets, the most coins counts them together and keeps the coins nearest their minimum")
    void maximumAcrossMarkets() {
        MarketProperties.VolumeSelection rules = rules();
        rules.setMaxSymbols(4);
        // Both fixed BTCs count, which leaves 2 places. Of the coins clearing, spot's GGG (130% of
        // its minimum) and AAA (120%) beat futures' AAA (110%), though that trades far more.
        VolumeRanking.Market futures = new VolumeRanking.Market("FUTURES",
                List.of(coin("AAA", 330)), List.of("BTCUSDT"), Set.of(), 300 * M);
        VolumeRanking.Market spot = new VolumeRanking.Market("SPOT",
                List.of(coin("AAA", 60), coin("GGG", 65)), List.of("BTCUSDT"), Set.of(), 50 * M);

        assertThat(across(rules, futures, spot)).containsExactly(
                Map.entry("FUTURES", List.of()),
                Map.entry("SPOT", List.of("GGGUSDT", "AAAUSDT")));
    }

    @Test
    @DisplayName("a market whose volumes could not be read counts as it is towards the others' minimum")
    void unreadMarketCountsAsItIs() {
        MarketProperties.VolumeSelection rules = rules();
        rules.setMinSymbols(5);
        // Spot could not be asked: its 3 current coins count, none ranked; futures fills the rest.
        VolumeRanking.Market futures = new VolumeRanking.Market("FUTURES",
                List.of(coin("AAA", 100), coin("BBB", 90), coin("CCC", 80)), List.of(), Set.of(), 300 * M);
        VolumeRanking.Market spot = new VolumeRanking.Market("SPOT",
                List.of(), List.of("BTCUSDT", "XXXUSDT", "YYYUSDT"), Set.of(), 50 * M);

        assertThat(across(rules, futures, spot)).containsExactly(
                Map.entry("FUTURES", List.of("AAAUSDT", "BBBUSDT")),
                Map.entry("SPOT", List.of()));
    }
}
