package com.cryptosignals.backtest;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.LevelAnalysisEngineFactory;
import com.cryptosignals.analysis.model.AnalysisResult;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.fixtures.Candles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BacktestResultTest {

    private static final Timeframe TF = Timeframe.M5;
    private final ObjectMapper mapper = new ObjectMapper();
    private BacktestResult result;

    @BeforeEach
    void runBacktest() {
        // Three touches, then a decisive rally that breaks the level.
        AnalysisResult analysis = new LevelAnalysisEngineFactory().analyze(
                new MarketKey("BTCUSDT", TF), AnalysisConfig.defaults(),
                Candles.fromPath(TF, Candles.zigzag(99_000, 15,
                        100_000, 99_000, 100_000, 99_000, 100_000, 101_000)));

        result = new BacktestResult("BTCUSDT", "5m",
                Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-01-02T00:00:00Z"),
                analysis.candlesProcessed(), 42, analysis.configFingerprint(), "swing-cluster/fractal",
                analysis.levels(), analysis.signals(), analysis.breakouts());
    }

    @Test
    @DisplayName("counts levels, qualified levels and signals separately")
    void summarisesCounts() {
        BacktestResult.Summary summary = result.summary(3);

        assertThat(summary.levelsDiscovered()).isEqualTo(result.levels().size());
        assertThat(summary.signals()).isEqualTo(1);
        assertThat(summary.resistanceSignals()).isEqualTo(1);
        assertThat(summary.qualifiedLevels()).isGreaterThanOrEqualTo(1);
        assertThat(summary.brokenLevels()).isEqualTo(1);
    }

    @Test
    @DisplayName("measures how long a level kept holding after it signalled")
    void measuresHoldDuration() {
        var outcomes = result.outcomes();

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).broken()).isTrue();
        // The signal fired on touch 3 at index 74 and the breakout confirmed at index 79.
        assertThat(outcomes.get(0).candlesHeld()).isEqualTo(5);
        assertThat(result.summary(3).medianCandlesHeldAfterSignal()).isEqualTo(5);
        assertThat(result.summary(3).signalsWhoseLevelLaterBroke()).isEqualTo(1);
        assertThat(result.summary(3).signalsStillUnbroken()).isZero();
    }

    @Test
    @DisplayName("a level still standing at the end is reported as unbroken, not as a failure")
    void treatsUnbrokenLevelsAsCensored() {
        AnalysisResult analysis = new LevelAnalysisEngineFactory().analyze(
                new MarketKey("BTCUSDT", TF), AnalysisConfig.defaults(),
                Candles.fromPath(TF, Candles.zigzag(99_000, 15,
                        100_000, 99_000, 100_000, 99_000, 100_000, 99_500)));
        BacktestResult holding = new BacktestResult("BTCUSDT", "5m",
                Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-01-02T00:00:00Z"),
                analysis.candlesProcessed(), 1, analysis.configFingerprint(), "s",
                analysis.levels(), analysis.signals(), analysis.breakouts());

        assertThat(holding.outcomes()).singleElement()
                .satisfies(outcome -> assertThat(outcome.broken()).isFalse());
        assertThat(holding.summary(3).signalsStillUnbroken()).isEqualTo(1);
        assertThat(holding.summary(3).stillUnbrokenRatio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("exports valid JSON carrying every touch")
    void exportsJson() throws Exception {
        String json = new BacktestExporter(mapper).toJson(result, 3);
        JsonNode root = mapper.readTree(json);

        assertThat(root.path("symbol").asText()).isEqualTo("BTCUSDT");
        assertThat(root.path("summary").path("signals").asInt()).isEqualTo(1);
        assertThat(root.path("signals").get(0).path("touches")).hasSize(3);
        assertThat(root.path("levels").isArray()).isTrue();
    }

    @Test
    @DisplayName("exports CSV with one row per signal and full-precision prices")
    void exportsCsv() {
        String csv = new BacktestExporter(mapper).toCsv(result);
        String[] lines = csv.strip().split("\n");

        assertThat(lines[0]).startsWith("signal_id,level_id,symbol,timeframe,type");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).contains("BTCUSDT,5m,RESISTANCE,100000");
        // No thousands separators: a comma inside a field would corrupt the row.
        assertThat(lines[1]).doesNotContain("\"");
        assertThat(lines[1].split(",")).hasSize(18);
    }

    @Test
    @DisplayName("the console report states the counts the specification asks for")
    void producesReport() {
        String report = new BacktestExporter(mapper).toReport(result, 3);

        assertThat(report)
                .contains("BTCUSDT")
                .contains("Candles analysed:")
                .contains("Levels discovered:")
                .contains("Valid 3-touch levels:")
                .contains("Signals:")
                .contains("Broken levels:")
                .contains("Candles held after signal");
    }
}
