package com.cryptosignals.marketdata;

import com.cryptosignals.config.ExchangeProperties;
import com.cryptosignals.config.MarketProperties;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.exchange.ExchangeClient;
import com.cryptosignals.exchange.ExchangeException;
import com.cryptosignals.exchange.SymbolActivity;
import com.cryptosignals.fixtures.Candles;
import com.cryptosignals.persistence.AnalysisResultStore;
import com.cryptosignals.persistence.entity.MarketEntity;
import com.cryptosignals.persistence.jdbc.CandleJdbcRepository;
import com.cryptosignals.persistence.jdbc.WatchedSymbolRepository;
import com.cryptosignals.persistence.jdbc.WatchedSymbolRepository.WatchedSymbol;
import com.cryptosignals.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * The watch list follows trading volume while running: coins are chosen and stored, their markets
 * started, and a coin that falls away is stopped with its stored levels retired.
 *
 * <p>On the 4h timeframe, which no other test uses, so retiring unwatched markets here cannot touch
 * another test's data.
 */
@TestPropertySource(properties = {
        "crypto-signals.markets.symbols=FIXEDUSDT",
        "crypto-signals.markets.timeframes=4h",
        "crypto-signals.markets.volume-selection.enabled=true",
        "crypto-signals.markets.volume-selection.window=1d",
        "crypto-signals.markets.volume-selection.min-daily-volume=1000",
        "crypto-signals.markets.volume-selection.max-symbols=3",
        // Only the test refreshes.
        "crypto-signals.markets.volume-selection.refresh-interval=3650d",
        "crypto-signals.markets.volume-selection.retry-interval=3650d"
})
class WatchListIT extends PostgresIntegrationTest {

    private static final Timeframe TF = Timeframe.H4;

    @MockitoBean ExchangeClient exchange;
    @Autowired WatchList watchList;
    @Autowired MarketDataBootstrap bootstrap;
    @Autowired MarketAnalysisService analysisService;
    @Autowired AnalysisResultStore store;
    @Autowired CandleJdbcRepository candles;
    @Autowired WatchedSymbolRepository stored;
    @Autowired MarketProperties properties;
    @Autowired ExchangeProperties exchangeProperties;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void bootstrapDone() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (bootstrap.state() != MarketDataBootstrap.State.READY && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(bootstrap.state()).isEqualTo(MarketDataBootstrap.State.READY);
        when(exchange.supportsSymbol(anyString())).thenReturn(true);
    }

    private static SymbolActivity coin(String base, double dailyVolume, String... tags) {
        return new SymbolActivity(base + "USDT", base, dailyVolume, 110, 100, Set.of(tags));
    }

    private void exchangeReports(SymbolActivity... activity) {
        doReturn(List.of(activity)).when(exchange).tradingActivity(any(), any());
    }

    /** Three touches of a 100k resistance with supports below, ending now: leaves ACTIVE levels. */
    private void storeHistory(String symbol) {
        double[] path = Candles.zigzag(95_000, 6,
                105_000, 96_000, 107_000, 96_000, 102_000, 96_000, 104_000, 96_000, 101_500,
                99_000, 100_000, 99_000, 100_000, 99_000, 100_000, 99_500);
        long start = TF.alignDown(System.currentTimeMillis()) - (long) (path.length + 2) * TF.durationMillis();
        List<Candle> series = Candles.fromPath(TF, start, path);
        MarketEntity market = store.resolveMarket(exchangeProperties.getName(), new MarketKey(symbol, TF));
        candles.upsert(market.getId(), series);
    }

    private long marketId(String symbol) {
        return store.resolveMarket(exchangeProperties.getName(), new MarketKey(symbol, TF)).getId();
    }

    private int count(String table, String symbol, String status) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE market_id = ? AND status = ?",
                Integer.class, marketId(symbol), status);
    }

    @Test
    @DisplayName("coins are chosen by volume and started; one that falls away is stopped and its levels retired")
    void followsVolume() {
        storeHistory("BBBUSDT");
        exchangeReports(coin("AAA", 5000), coin("BBB", 3000), coin("CCC", 500), coin("USDC", 9000, "stablecoin"));

        assertThat(watchList.refresh()).hasValueSatisfying(change ->
                assertThat(change.added()).containsExactly("AAAUSDT", "BBBUSDT"));
        assertThat(watchList.symbols()).containsExactly("FIXEDUSDT", "AAAUSDT", "BBBUSDT");
        assertThat(stored.findAll(exchangeProperties.getName())).extracting(WatchedSymbol::symbol)
                .containsExactly("AAAUSDT", "BBBUSDT");

        bootstrap.applyWatchList();
        for (String symbol : List.of("FIXEDUSDT", "AAAUSDT", "BBBUSDT")) {
            assertThat(analysisService.engine(new MarketKey(symbol, TF))).as(symbol).isPresent();
        }
        assertThat(analysisService.engine(new MarketKey("CCCUSDT", TF))).isEmpty();
        assertThat(count("price_level", "BBBUSDT", "ACTIVE")).isPositive();
        assertThat(count("signal", "BBBUSDT", "ACTIVE")).isPositive();

        // AAA slips, but stays above 70% of the minimum; BBB falls below it.
        exchangeReports(coin("AAA", 800), coin("BBB", 600), coin("CCC", 500));
        assertThat(watchList.refresh()).hasValueSatisfying(change -> {
            assertThat(change.added()).isEmpty();
            assertThat(change.removed()).containsExactly("BBBUSDT");
        });
        bootstrap.applyWatchList();

        assertThat(watchList.symbols()).containsExactly("FIXEDUSDT", "AAAUSDT");
        assertThat(analysisService.engine(new MarketKey("BBBUSDT", TF))).isEmpty();
        assertThat(analysisService.engine(new MarketKey("AAAUSDT", TF))).isPresent();
        assertThat(count("price_level", "BBBUSDT", "ACTIVE")).isZero();
        assertThat(count("price_level", "BBBUSDT", "EXPIRED")).isPositive();
        assertThat(count("signal", "BBBUSDT", "ACTIVE")).isZero();

        // Back above the minimum: watched again, and its replay restores the levels still active.
        exchangeReports(coin("AAA", 5000), coin("BBB", 3000));
        assertThat(watchList.refresh()).hasValueSatisfying(change ->
                assertThat(change.added()).containsExactly("BBBUSDT"));
        bootstrap.applyWatchList();
        assertThat(count("price_level", "BBBUSDT", "ACTIVE")).isPositive();
    }

    @Test
    @DisplayName("a failed refresh keeps the list; a restart that cannot ask the exchange uses the stored list")
    void survivesAnUnreachableExchange() {
        List<String> before = watchList.symbols();
        doThrow(new ExchangeException("Binance is down", null)).when(exchange).tradingActivity(any(), any());

        assertThat(watchList.refresh()).isEmpty();
        assertThat(watchList.symbols()).isEqualTo(before);
        assertThat(watchList.lastError()).contains("Binance is down");

        // As after a restart: a new instance over the same table.
        Instant now = Instant.now();
        stored.replaceAll(exchangeProperties.getName(), List.of(
                new WatchedSymbol("ZZZUSDT", 4000, now.minus(Duration.ofDays(3)), now)));
        ExchangeClient unreachable = Mockito.mock(ExchangeClient.class);
        when(unreachable.tradingActivity(any(), any())).thenThrow(new ExchangeException("offline", null));
        WatchList restarted = new WatchList(properties, unreachable, stored, exchangeProperties);

        restarted.initialise();

        assertThat(restarted.symbols()).containsExactly("FIXEDUSDT", "ZZZUSDT");
        assertThat(restarted.source()).isEqualTo(WatchList.Source.STORED);
        assertThat(restarted.isSettled()).isTrue();
        assertThat(restarted.nextAttemptAt()).isNotNull();

        // A first run (nothing stored) that cannot ask the exchange retires nothing.
        stored.replaceAll(exchangeProperties.getName(), List.of());
        WatchList firstRun = new WatchList(properties, unreachable, stored, exchangeProperties);
        firstRun.initialise();
        assertThat(firstRun.symbols()).containsExactly("FIXEDUSDT");
        assertThat(firstRun.isSettled()).isFalse();
    }
}
