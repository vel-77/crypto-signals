package com.cryptosignals.marketdata;

import com.cryptosignals.config.MarketProperties;
import com.cryptosignals.exchange.SymbolActivity;
import com.cryptosignals.marketdata.VolumeRanking.Ranked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
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
        assertThatThrownBy(() -> rules.setMinDailyVolume(-5)).isInstanceOf(IllegalArgumentException.class);
    }
}
