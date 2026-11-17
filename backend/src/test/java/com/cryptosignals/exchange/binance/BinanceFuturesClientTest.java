package com.cryptosignals.exchange.binance;

import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.exchange.ExchangeException;
import com.cryptosignals.exchange.ExchangeSettings;
import com.cryptosignals.exchange.SymbolActivity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link BinanceExchangeClient} on USDⓈ-M futures, against a local stand-in for Binance. */
class BinanceFuturesClientTest {

    private static final long HOUR = 3_600_000L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> klineQueries = new CopyOnWriteArrayList<>();
    private final AtomicInteger exchangeInfoCalls = new AtomicInteger();
    private final Set<String> failingSymbols = new java.util.concurrent.CopyOnWriteArraySet<>();
    private volatile boolean includeForming = true;
    private HttpServer server;
    private String base;

    private static String contract(String symbol, String base, String quote, String status,
                                   String contractType, String underlyingType) {
        return """
                {"symbol":"%s","status":"%s","baseAsset":"%s","quoteAsset":"%s",
                 "contractType":"%s","underlyingType":"%s"}""".formatted(
                symbol, status, base, quote, contractType, underlyingType);
    }

    private static final String EXCHANGE_INFO = "{\"symbols\":[" + String.join(",",
            contract("BTCUSDT", "BTC", "USDT", "TRADING", "PERPETUAL", "COIN"),
            contract("USDCUSDT", "USDC", "USDT", "TRADING", "PERPETUAL", "COIN"),
            contract("1000SHIBUSDT", "1000SHIB", "USDT", "TRADING", "PERPETUAL", "COIN"),
            contract("XAUUSDT", "XAU", "USDT", "TRADING", "TRADIFI_PERPETUAL", "COMMODITY"),
            contract("TSLAUSDT", "TSLA", "USDT", "TRADING", "TRADIFI_PERPETUAL", "EQUITY"),
            contract("BTCDOMUSDT", "BTCDOM", "USDT", "TRADING", "PERPETUAL", "INDEX"),
            contract("BTCUSDT_261225", "BTC", "USDT", "TRADING", "CURRENT_QUARTER", "COIN"),
            contract("OLDUSDT", "OLD", "USDT", "SETTLING", "PERPETUAL", "COIN"),
            contract("ETHUSDC", "ETH", "USDC", "TRADING", "PERPETUAL", "COIN")) + "]}";

    private static final String PRODUCTS = """
            {"data":[{"s":"USDCUSDT","b":"USDC","tags":["stablecoin"]},
                     {"s":"USDCEUR","b":"USDC","tags":["fiat-pair"]},
                     {"s":"BTCUSDT","b":"BTC","tags":["pow"]}]}""";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fapi/v1/exchangeInfo", ex -> {
            exchangeInfoCalls.incrementAndGet();
            reply(ex, 200, EXCHANGE_INFO);
        });
        server.createContext("/fapi/v1/klines", this::klines);
        server.createContext("/fapi/v1/time", ex -> reply(ex, 200, "{\"serverTime\":1700000000000}"));
        server.createContext("/products", ex -> reply(ex, 200, PRODUCTS));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private BinanceExchangeClient client() {
        ExchangeSettings defaults = ExchangeSettings.defaults();
        ExchangeSettings settings = new ExchangeSettings(base, "ws://127.0.0.1:1", defaults.connectTimeout(),
                defaults.readTimeout(), defaults.klinePageSize(), 100_000, 1, Duration.ofMillis(1),
                defaults.reconnectMinBackoff(), defaults.reconnectMaxBackoff(), defaults.streamStaleAfter(),
                base + "/products");
        return new BinanceExchangeClient(settings, mapper, BinanceMarket.USDM_FUTURES);
    }

    /**
     * As many hourly candles as asked for, the newest still forming unless {@link #includeForming}
     * is off. Each traded 10 USDT except the oldest, which traded 1000, so whether it is counted
     * shows in the sum. Prices climb by 1 an hour: highs from 101, lows from 99.
     */
    private void klines(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getRawQuery();
        klineQueries.add(query);
        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        if (failingSymbols.contains(params.get("symbol"))) {
            reply(ex, 400, "{\"code\":-1121,\"msg\":\"Invalid symbol.\"}");
            return;
        }
        int limit = Integer.parseInt(params.get("limit"));
        long newestOpen = System.currentTimeMillis() / HOUR * HOUR - (includeForming ? 0 : HOUR);
        List<List<Object>> rows = new ArrayList<>();
        for (int i = limit - 1; i >= 0; i--) {
            long open = newestOpen - i * HOUR;
            int index = limit - 1 - i;
            String quoteVolume = index == 0 ? "1000" : "10";
            String mid = String.valueOf(100 + index);
            rows.add(List.of(open, mid, String.valueOf(101 + index), String.valueOf(99 + index), mid,
                    "1", open + HOUR - 1, quoteVolume, 1, "0", "0", "0"));
        }
        reply(ex, 200, mapper.writeValueAsString(rows));
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    @DisplayName("lists crypto perpetuals of the quote asset: no stock, commodity, index or quarterly contracts")
    void perpetualsOnly() {
        List<SymbolActivity> activity = client().tradingActivity("usdt", Duration.ofDays(7));

        assertThat(activity).extracting(SymbolActivity::symbol)
                .containsExactly("BTCUSDT", "USDCUSDT", "1000SHIBUSDT");
        assertThat(activity).extracting(SymbolActivity::baseAsset).containsExactly("BTC", "USDC", "1000SHIB");
    }

    @Test
    @DisplayName("volume, high and low come from the closed hourly candles of the window")
    void volumeFromHourlyCandles() {
        SymbolActivity btc = client().tradingActivity("USDT", Duration.ofDays(7)).get(0);

        // 169 asked for: the newest is still forming, which leaves the 168 hours of the window.
        assertThat(klineQueries).allSatisfy(q -> assertThat(q).contains("interval=1h").contains("limit=169"));
        assertThat(btc.quoteVolume()).isEqualTo(1000 + 167 * 10.0);
        assertThat(btc.lowPrice()).isEqualTo(99);           // the oldest candle
        assertThat(btc.highPrice()).isEqualTo(101 + 167);   // the newest closed one
    }

    @Test
    @DisplayName("when no candle is forming yet, the oldest one falls outside the window")
    void onlyTheWindowCounts() {
        includeForming = false;

        SymbolActivity btc = client().tradingActivity("USDT", Duration.ofDays(7)).get(0);

        assertThat(btc.quoteVolume()).isEqualTo(168 * 10.0);
        assertThat(btc.lowPrice()).isEqualTo(100);
        assertThat(btc.highPrice()).isEqualTo(101 + 168);
    }

    @Test
    @DisplayName("a symbol takes the tags of the spot coin with its base asset")
    void tagsByBaseAsset() {
        List<SymbolActivity> activity = client().tradingActivity("USDT", Duration.ofDays(7));

        assertThat(activity.get(0).tags()).containsExactly("pow");
        assertThat(activity.get(1).tags()).containsExactlyInAnyOrder("stablecoin", "fiat-pair");
        assertThat(activity.get(2).tags()).isEmpty();
    }

    @Test
    @DisplayName("one symbol that cannot be asked fails the whole answer, rather than look inactive")
    void failsWhole() {
        failingSymbols.add("USDCUSDT");

        assertThatThrownBy(() -> client().tradingActivity("USDT", Duration.ofDays(7)))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("Invalid symbol");
    }

    @Test
    @DisplayName("checks symbols against one fetched list, not one request each")
    void supportsSymbol() {
        BinanceExchangeClient client = client();

        assertThat(client.supportsSymbol("BTCUSDT")).isTrue();
        assertThat(client.supportsSymbol("1000SHIBUSDT")).isTrue();
        assertThat(client.supportsSymbol("XAUUSDT")).isTrue();      // named on purpose: allowed
        assertThat(client.supportsSymbol("SHIBUSDT")).isFalse();    // spot spelling
        assertThat(client.supportsSymbol("OLDUSDT")).isFalse();     // settling
        assertThat(exchangeInfoCalls).hasValue(1);
    }

    @Test
    @DisplayName("candles and the clock come from the futures API")
    void futuresEndpoints() {
        BinanceExchangeClient client = client();

        List<Candle> latest = client.fetchLatestCandles("BTCUSDT", Timeframe.H1, 5);
        assertThat(latest).hasSize(4);   // the forming one is dropped
        assertThat(client.serverTime().toEpochMilli()).isEqualTo(1_700_000_000_000L);
        assertThat(client.name()).isEqualTo("BINANCE_FUTURES");
        assertThat(client.label()).isEqualTo("Binance USDⓈ-M futures");
    }

    @Test
    @DisplayName("futures windows are whole hours, up to what one request returns")
    void windowHours() {
        assertThat(BinanceExchangeClient.windowHours(Duration.ofDays(7))).isEqualTo(168);
        assertThat(BinanceExchangeClient.windowHours(Duration.ofHours(1))).isEqualTo(1);
        assertThatThrownBy(() -> BinanceExchangeClient.windowHours(Duration.ofMinutes(90)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BinanceExchangeClient.windowHours(Duration.ofHours(1500)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("request weights follow Binance's published tables")
    void weights() {
        assertThat(BinanceMarket.SPOT.klinesWeight(1000)).isEqualTo(2);
        assertThat(BinanceMarket.USDM_FUTURES.klinesWeight(99)).isEqualTo(1);
        assertThat(BinanceMarket.USDM_FUTURES.klinesWeight(169)).isEqualTo(2);
        assertThat(BinanceMarket.USDM_FUTURES.klinesWeight(500)).isEqualTo(5);
        assertThat(BinanceMarket.USDM_FUTURES.klinesWeight(1000)).isEqualTo(5);
        assertThat(BinanceMarket.USDM_FUTURES.klinesWeight(1500)).isEqualTo(10);
        assertThat(BinanceMarket.byExchangeName(" binance_futures ")).contains(BinanceMarket.USDM_FUTURES);
        assertThat(BinanceMarket.byExchangeName("BINANCE")).contains(BinanceMarket.SPOT);
        assertThat(BinanceMarket.byExchangeName("KRAKEN")).isEmpty();
    }
}
