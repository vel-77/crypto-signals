package com.cryptosignals.api;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.LevelAnalysisEngineFactory;
import com.cryptosignals.analysis.engine.LevelAnalysisEngine;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.fixtures.Candles;
import com.cryptosignals.persistence.AnalysisResultStore;
import com.cryptosignals.persistence.entity.MarketEntity;
import com.cryptosignals.persistence.jdbc.CandleJdbcRepository;
import com.cryptosignals.persistence.repository.PriceLevelRepository;
import com.cryptosignals.persistence.repository.SignalRepository;
import com.cryptosignals.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end checks of the query API against a real database holding real analysis output. */
@AutoConfigureMockMvc
class ApiIT extends PostgresIntegrationTest {

    private static final Timeframe TF = Timeframe.M5;

    @Autowired MockMvc mvc;
    @Autowired AnalysisResultStore store;
    @Autowired CandleJdbcRepository candles;
    @Autowired PriceLevelRepository levels;
    @Autowired SignalRepository signals;

    private UUID signalId;
    private UUID levelId;

    @BeforeEach
    void seed() {
        MarketKey key = new MarketKey("BTCUSDT", TF);
        MarketEntity market = store.resolveMarket("BINANCE", key);
        candles.deleteRange(market.getId(), Instant.EPOCH, Instant.now().plusSeconds(86_400_000L));
        levels.deleteAll();

        List<Candle> series = Candles.fromPath(TF, Candles.zigzag(99_000, 15,
                100_000, 99_000, 100_000, 99_000, 100_000, 99_500));
        candles.upsert(market.getId(), series);

        AnalysisConfig config = AnalysisConfig.defaults();
        LevelAnalysisEngineFactory factory = new LevelAnalysisEngineFactory();
        LevelAnalysisEngine engine = factory.create(key, config, false);
        for (Candle candle : series) {
            store.apply(market, engine.onClosedCandle(candle),
                    factory.detectionStrategyName(), config.fingerprint());
        }

        signalId = signals.findAll().get(0).getId();
        levelId = signals.findAll().get(0).getLevelId();
    }

    @Test
    @DisplayName("GET /api/signals returns the emitted signal and supports filters")
    void listSignals() throws Exception {
        mvc.perform(get("/api/signals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.items[0].symbol").value("BTCUSDT"))
                .andExpect(jsonPath("$.items[0].timeframe").value("5m"))
                .andExpect(jsonPath("$.items[0].type").value("RESISTANCE"))
                .andExpect(jsonPath("$.items[0].touchCount").value(3))
                .andExpect(jsonPath("$.totalItems").value(1));

        mvc.perform(get("/api/signals").param("symbol", "BTCUSDT").param("timeframe", "5m")
                        .param("type", "RESISTANCE").param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)));

        mvc.perform(get("/api/signals").param("symbol", "ETHUSDT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(0)));

        mvc.perform(get("/api/signals").param("type", "SUPPORT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/signals/{id} includes every touch")
    void signalDetail() throws Exception {
        mvc.perform(get("/api/signals/{id}", signalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.touches", org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$.touches[0].touchNumber").value(1))
                .andExpect(jsonPath("$.touches[2].touchNumber").value(3));

        mvc.perform(get("/api/signals/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/levels and its touch listing")
    void levelsAndTouches() throws Exception {
        mvc.perform(get("/api/levels").param("minTouches", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.items[0].type").value("RESISTANCE"));

        mvc.perform(get("/api/levels/{id}", levelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.touches", org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mvc.perform(get("/api/levels/{id}/touches", levelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(3)));

        mvc.perform(get("/api/levels/{id}/breakout", levelId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/candles returns stored OHLCV for charting")
    void candles() throws Exception {
        mvc.perform(get("/api/candles").param("symbol", "BTCUSDT").param("timeframe", "5m")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(org.hamcrest.Matchers.greaterThan(0))))
                .andExpect(jsonPath("$[0].open").exists())
                .andExpect(jsonPath("$[0].openTime").exists());

        mvc.perform(get("/api/candles").param("symbol", "NOSUCH").param("timeframe", "5m"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/config exposes the rules but never a secret")
    void config() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.minTouches").value(3))
                .andExpect(jsonPath("$.analysis.touchTolerancePercent").value(0.1))
                .andExpect(jsonPath("$.configFingerprint").exists())
                .andExpect(jsonPath("$.detectionStrategy").value(
                        org.hamcrest.Matchers.containsString("swing-cluster")))
                .andExpect(jsonPath("$.notifications.telegramEnabled").value(false))
                .andExpect(jsonPath("$.notifications.botToken").doesNotExist())
                .andExpect(jsonPath("$.notifications.telegram").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/status reports pipeline health")
    void statusEndpoint() throws Exception {
        mvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bootstrap").exists())
                .andExpect(jsonPath("$.liveStream.running").value(false));
    }

    @Test
    @DisplayName("POST /api/backtest replays stored candles in every export format")
    void backtest() throws Exception {
        mvc.perform(post("/api/backtest")
                        .param("symbol", "BTCUSDT").param("timeframe", "5m")
                        .param("from", "2024-12-31T00:00:00Z")
                        .param("to", "2025-01-02T00:00:00Z")
                        .param("format", "json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.signals").value(1))
                .andExpect(jsonPath("$.summary.levelsDiscovered")
                        .value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.signals[0].touches", org.hamcrest.Matchers.hasSize(3)));

        mvc.perform(post("/api/backtest")
                        .param("symbol", "BTCUSDT").param("timeframe", "5m")
                        .param("from", "2024-12-31T00:00:00Z")
                        .param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("signal_id,level_id")));

        mvc.perform(post("/api/backtest")
                        .param("symbol", "BTCUSDT").param("timeframe", "5m")
                        .param("from", "2024-12-31T00:00:00Z")
                        .param("format", "report"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Levels discovered:")));
    }

    @Test
    @DisplayName("a bad parameter produces a structured 400, not a stack trace")
    void badRequest() throws Exception {
        mvc.perform(get("/api/candles").param("symbol", "BTCUSDT").param("timeframe", "7q"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Unsupported timeframe")));
    }
}
