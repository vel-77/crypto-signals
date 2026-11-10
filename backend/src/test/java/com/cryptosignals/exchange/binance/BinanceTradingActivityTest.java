package com.cryptosignals.exchange.binance;

import com.cryptosignals.exchange.ExchangeException;
import com.cryptosignals.exchange.ExchangeSettings;
import com.cryptosignals.exchange.SymbolActivity;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link BinanceExchangeClient#tradingActivity} against a local stand-in for Binance. */
class BinanceTradingActivityTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> tickerQueries = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String base;
    private volatile String exchangeInfo;
    private volatile String products;
    private volatile int productsStatus = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/exchangeInfo", ex -> reply(ex, 200, exchangeInfo));
        server.createContext("/api/v3/ticker", this::ticker);
        server.createContext("/products", ex -> reply(ex, productsStatus, products));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private BinanceExchangeClient client() {
        ExchangeSettings settings = ExchangeSettings.defaults()
                .withRestBaseUrl(base)
                .withSymbolTagsUrl(base + "/products");
        settings = new ExchangeSettings(settings.restBaseUrl(), settings.wsBaseUrl(), settings.connectTimeout(),
                settings.readTimeout(), settings.klinePageSize(), settings.maxWeightPerMinute(), 1,
                Duration.ofMillis(1), settings.reconnectMinBackoff(), settings.reconnectMaxBackoff(),
                settings.streamStaleAfter(), settings.symbolTagsUrl());
        return new BinanceExchangeClient(settings, mapper);
    }

    /** Answers a rolling-window ticker request for whatever symbols it lists. */
    private void ticker(HttpExchange ex) throws IOException {
        String query = URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
        tickerQueries.add(query);
        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        JsonNode symbols = mapper.readTree(params.get("symbols"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode symbol : symbols) {
            rows.add(Map.of("symbol", symbol.asText(), "quoteVolume", "1000.0",
                    "highPrice", "110", "lowPrice", "100", "lastPrice", "105"));
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

    private static String symbol(String symbol, String base, String quote, String status) {
        return """
                {"symbol":"%s","status":"%s","baseAsset":"%s","quoteAsset":"%s","isSpotTradingAllowed":true}"""
                .formatted(symbol, status, base, quote);
    }

    @Test
    @DisplayName("lists trading pairs of the quote asset with their volume, price range and tags")
    void activity() {
        exchangeInfo = """
                {"symbols":[%s,%s,%s,%s,%s]}""".formatted(
                symbol("BTCUSDT", "BTC", "USDT", "TRADING"),
                symbol("USDCUSDT", "USDC", "USDT", "TRADING"),
                symbol("ETHBTC", "ETH", "BTC", "TRADING"),
                symbol("OLDUSDT", "OLD", "USDT", "BREAK"),
                symbol("币安人生USDT", "币安人生", "USDT", "TRADING"));
        products = """
                {"code":"000000","data":[{"s":"BTCUSDT","tags":["Payments","mining-zone"]},
                {"s":"USDCUSDT","tags":["stablecoin"]}]}""";

        List<SymbolActivity> activity = client().tradingActivity("usdt", Duration.ofDays(7));

        assertThat(activity).extracting(SymbolActivity::symbol).containsExactly("BTCUSDT", "USDCUSDT");
        assertThat(activity.get(0).baseAsset()).isEqualTo("BTC");
        assertThat(activity.get(0).quoteVolume()).isEqualTo(1000.0);
        assertThat(activity.get(0).priceRangePercent()).isEqualTo(10.0);
        assertThat(activity.get(0).tags()).containsExactlyInAnyOrder("Payments", "mining-zone");
        assertThat(activity.get(1).tags()).containsExactly("stablecoin");
        assertThat(tickerQueries).singleElement().satisfies(q -> {
            assertThat(q).contains("windowSize=7d").contains("type=MINI")
                    .contains("symbols=[\"BTCUSDT\",\"USDCUSDT\"]");
        });
    }

    @Test
    @DisplayName("asks for volumes 100 symbols at a time")
    void batches() {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            rows.add(symbol("C" + i + "USDT", "C" + i, "USDT", "TRADING"));
        }
        exchangeInfo = "{\"symbols\":[" + String.join(",", rows) + "]}";
        products = "{\"data\":[{\"s\":\"C1USDT\",\"tags\":[]}]}";

        List<SymbolActivity> activity = client().tradingActivity("USDT", Duration.ofHours(24));

        assertThat(activity).hasSize(250);
        assertThat(tickerQueries).hasSize(3).allSatisfy(q -> assertThat(q).contains("windowSize=1d"));
    }

    @Test
    @DisplayName("fails rather than answer without tags: stablecoins would look like coins")
    void failsWithoutTags() {
        exchangeInfo = "{\"symbols\":[" + symbol("BTCUSDT", "BTC", "USDT", "TRADING") + "]}";
        products = "{\"code\":\"500\",\"message\":\"busy\"}";
        productsStatus = 503;

        assertThatThrownBy(() -> client().tradingActivity("USDT", Duration.ofDays(7)))
                .isInstanceOf(ExchangeException.class);
    }

    @Test
    @DisplayName("windows are written the way Binance expects them")
    void windowSize() {
        assertThat(BinanceExchangeClient.windowSize(Duration.ofDays(7))).isEqualTo("7d");
        assertThat(BinanceExchangeClient.windowSize(Duration.ofDays(1))).isEqualTo("1d");
        assertThat(BinanceExchangeClient.windowSize(Duration.ofHours(6))).isEqualTo("6h");
        assertThat(BinanceExchangeClient.windowSize(Duration.ofMinutes(30))).isEqualTo("30m");
        assertThatThrownBy(() -> BinanceExchangeClient.windowSize(Duration.ofDays(8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BinanceExchangeClient.windowSize(Duration.ofMinutes(90)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
