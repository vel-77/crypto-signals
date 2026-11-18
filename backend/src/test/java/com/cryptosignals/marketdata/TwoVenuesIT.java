package com.cryptosignals.marketdata;

import com.cryptosignals.analysis.LevelAnalysisEngineFactory;
import com.cryptosignals.config.Venue;
import com.cryptosignals.config.Venues;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.exchange.ExchangeClient;
import com.cryptosignals.exchange.ExchangeSettings;
import com.cryptosignals.exchange.SymbolActivity;
import com.cryptosignals.fixtures.Candles;
import com.cryptosignals.persistence.AnalysisResultStore;
import com.cryptosignals.persistence.entity.MarketEntity;
import com.cryptosignals.persistence.jdbc.CandleJdbcRepository;
import com.cryptosignals.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spot and futures run side by side: each picks its own coins by its own minimum volume, the same
 * coin gets an engine on each, and the API answers for the market it is asked about.
 *
 * <p>On the 2h timeframe, which no other test uses, so retiring unwatched markets here cannot
 * touch another test's data.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "crypto-signals.markets.symbols=1000SHIBUSDT",
        "crypto-signals.markets.timeframes=2h",
        "crypto-signals.markets.volume-selection.enabled=true",
        "crypto-signals.markets.volume-selection.window=1d",
        "crypto-signals.markets.volume-selection.min-daily-volume-by-exchange=BINANCE_FUTURES:3000,BINANCE:500",
        "crypto-signals.markets.volume-selection.refresh-interval=3650d",
        "crypto-signals.markets.volume-selection.retry-interval=3650d"
})
class TwoVenuesIT extends PostgresIntegrationTest {

    private static final Timeframe TF = Timeframe.H2;

    private static final ExchangeClient futuresClient = Mockito.mock(ExchangeClient.class);
    private static final ExchangeClient spotClient = Mockito.mock(ExchangeClient.class);
    private static final Venue FUTURES = new Venue("BINANCE_FUTURES", "Binance USDⓈ-M futures", futuresClient,
            ExchangeSettings.defaults(), new LevelAnalysisEngineFactory().withIdNamespace("BINANCE_FUTURES"));
    private static final Venue SPOT = new Venue("BINANCE", "Binance spot", spotClient,
            ExchangeSettings.defaults(), new LevelAnalysisEngineFactory());

    @TestBean Venues venues;
    @Autowired WatchLists watchLists;
    @Autowired MarketDataBootstrap bootstrap;
    @Autowired MarketAnalysisService analysisService;
    @Autowired AnalysisResultStore store;
    @Autowired CandleJdbcRepository candles;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    static Venues venues() {
        // Futures first: the default market.
        return new Venues(List.of(FUTURES, SPOT));
    }

    @BeforeEach
    void bootstrapDone() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (bootstrap.state() != MarketDataBootstrap.State.READY && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(bootstrap.state()).isEqualTo(MarketDataBootstrap.State.READY);
        for (ExchangeClient client : List.of(futuresClient, spotClient)) {
            Mockito.reset(client);
            when(client.supportsSymbol(anyString())).thenReturn(true);
            when(client.listedSymbol(anyString())).thenAnswer(call -> Optional.of(call.getArgument(0)));
        }
        // Spot lists SHIB under its plain name.
        when(spotClient.listedSymbol("1000SHIBUSDT")).thenReturn(Optional.of("SHIBUSDT"));
    }

    private static SymbolActivity coin(String base, double dailyVolume) {
        return new SymbolActivity(base + "USDT", base, dailyVolume, 110, 100, Set.of());
    }

    /** Three touches of a 100k resistance with supports below, ending now: leaves ACTIVE levels. */
    private void storeHistory(Venue venue, String symbol) {
        double[] path = Candles.zigzag(95_000, 6,
                105_000, 96_000, 107_000, 96_000, 102_000, 96_000, 104_000, 96_000, 101_500,
                99_000, 100_000, 99_000, 100_000, 99_000, 100_000, 99_500);
        long start = TF.alignDown(System.currentTimeMillis()) - (long) (path.length + 2) * TF.durationMillis();
        List<Candle> series = Candles.fromPath(TF, start, path);
        candles.upsert(market(venue, symbol).getId(), series);
    }

    private MarketEntity market(Venue venue, String symbol) {
        return store.resolveMarket(venue.name(), new MarketKey(symbol, TF));
    }

    private List<String> levelIds(Venue venue, String symbol) {
        return jdbc.queryForList("SELECT id::text FROM price_level WHERE market_id = ?", String.class,
                market(venue, symbol).getId());
    }

    @Test
    @DisplayName("each market chooses its own coins by its own minimum and runs its own engines")
    void runSideBySide() throws Exception {
        storeHistory(FUTURES, "BBBUSDT");
        storeHistory(SPOT, "BBBUSDT");
        // Futures trade more: AAA and BBB clear futures' 3000; only BBB and CCC clear spot's 500.
        doReturn(List.of(coin("AAA", 9000), coin("BBB", 4000), coin("CCC", 1000)))
                .when(futuresClient).tradingActivity(any(), any());
        doReturn(List.of(coin("AAA", 300), coin("BBB", 800), coin("CCC", 600)))
                .when(spotClient).tradingActivity(any(), any());

        WatchList futures = watchLists.of(FUTURES);
        WatchList spot = watchLists.of(SPOT);
        futures.initialise();
        spot.initialise();
        bootstrap.applyWatchList(FUTURES);
        bootstrap.applyWatchList(SPOT);

        assertThat(futures.symbols()).containsExactly("1000SHIBUSDT", "AAAUSDT", "BBBUSDT");
        assertThat(spot.symbols()).containsExactly("SHIBUSDT", "BBBUSDT", "CCCUSDT");
        assertThat(analysisService.engine(FUTURES.name(), new MarketKey("AAAUSDT", TF))).isPresent();
        assertThat(analysisService.engine(SPOT.name(), new MarketKey("AAAUSDT", TF))).isEmpty();
        assertThat(analysisService.engine(SPOT.name(), new MarketKey("CCCUSDT", TF))).isPresent();

        // The same coin on both: two engines over two stored series, levels that never share an id.
        MarketEngine futuresBbb = analysisService.engine(FUTURES.name(), new MarketKey("BBBUSDT", TF)).orElseThrow();
        MarketEngine spotBbb = analysisService.engine(SPOT.name(), new MarketKey("BBBUSDT", TF)).orElseThrow();
        assertThat(futuresBbb).isNotSameAs(spotBbb);
        assertThat(futuresBbb.market().getId()).isNotEqualTo(spotBbb.market().getId());
        assertThat(levelIds(FUTURES, "BBBUSDT")).isNotEmpty()
                .doesNotContainAnyElementsOf(levelIds(SPOT, "BBBUSDT"));
        assertThat(levelIds(SPOT, "BBBUSDT")).hasSameSizeAs(levelIds(FUTURES, "BBBUSDT"));

        // The API answers for the market it is asked about; futures, listed first, by default.
        mvc.perform(get("/api/watchlist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchange").value("BINANCE_FUTURES"))
                .andExpect(jsonPath("$.rule.minDailyVolume").value(3000))
                .andExpect(jsonPath("$.symbols[0].symbol").value("1000SHIBUSDT"));
        mvc.perform(get("/api/watchlist").param("exchange", "binance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchange").value("BINANCE"))
                .andExpect(jsonPath("$.exchangeLabel").value("Binance spot"))
                .andExpect(jsonPath("$.rule.minDailyVolume").value(500))
                .andExpect(jsonPath("$.symbols[0].symbol").value("SHIBUSDT"))
                .andExpect(jsonPath("$.count").value(3));
        mvc.perform(get("/api/config").param("exchange", "BINANCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchange.name").value("BINANCE"))
                .andExpect(jsonPath("$.exchanges[0].name").value("BINANCE_FUTURES"))
                .andExpect(jsonPath("$.exchanges[1].name").value("BINANCE"))
                .andExpect(jsonPath("$.exchanges[1].minDailyVolume").value(500))
                .andExpect(jsonPath("$.markets.symbols[2]").value("CCCUSDT"))
                .andExpect(jsonPath("$.markets.volumeSelection.minDailyVolume").value(500));
        mvc.perform(get("/api/status").param("exchange", "BINANCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchange").value("BINANCE"))
                .andExpect(jsonPath("$.bootstrap").value("READY"))
                .andExpect(jsonPath("$.markets.length()").value(3))
                .andExpect(jsonPath("$.exchanges.length()").value(2));
        mvc.perform(get("/api/levels").param("symbol", "BBBUSDT").param("timeframe", "2h")
                        .param("exchange", "BINANCE_FUTURES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(levelIds(FUTURES, "BBBUSDT").size()));
        mvc.perform(get("/api/candles").param("symbol", "CCCUSDT").param("timeframe", "2h")
                        .param("exchange", "BINANCE_FUTURES"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/signals").param("exchange", "BYBIT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("BINANCE_FUTURES, BINANCE")));
    }
}
