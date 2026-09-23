package com.cryptosignals.backtest;

import com.cryptosignals.analysis.model.DetectedLevel;
import com.cryptosignals.analysis.model.Touch;
import com.cryptosignals.analysis.model.TradingSignal;
import com.cryptosignals.domain.PriceFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Renders a backtest for human inspection: a readable console report, JSON, or CSV.
 *
 * <p>Exporting exists so the detection can be argued with. A trader can open the CSV next to their
 * own chart and check, level by level, whether the algorithm saw what they see — which is the only
 * way to tell whether the encoded rules match the intent behind them.
 */
@Component
public class BacktestExporter {

    private final ObjectMapper mapper;

    public BacktestExporter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The console summary from specification section 18. */
    public String toReport(BacktestResult result, int minTouches) {
        BacktestResult.Summary summary = result.summary(minTouches);
        StringBuilder out = new StringBuilder(512);

        out.append(result.symbol()).append('\n');
        out.append(result.timeframe()).append('\n');
        out.append(PriceFormatter.timestamp(result.from())).append(" -> ")
                .append(PriceFormatter.timestamp(result.to())).append("\n\n");
        out.append("Candles analysed:      ").append(summary.candlesAnalysed()).append('\n');
        out.append("Levels discovered:     ").append(summary.levelsDiscovered()).append('\n');
        out.append("Valid ").append(minTouches).append("-touch levels: ")
                .append(summary.qualifiedLevels()).append('\n');
        out.append("Signals:               ").append(summary.signals())
                .append(" (").append(summary.resistanceSignals()).append(" resistance, ")
                .append(summary.supportSignals()).append(" support)\n");
        out.append("Levels broken later:   ").append(summary.signalsWhoseLevelLaterBroke())
                .append(" of ").append(summary.signals())
                .append(" signalled levels eventually broke\n");
        out.append("Still unbroken at end: ").append(summary.signalsStillUnbroken())
                .append(String.format(Locale.ROOT, "  (%.0f%%)", summary.stillUnbrokenRatio() * 100))
                .append('\n');
        out.append("Candles held after signal (p25 / median / p75): ")
                .append(summary.p25CandlesHeldAfterSignal()).append(" / ")
                .append(summary.medianCandlesHeldAfterSignal()).append(" / ")
                .append(summary.p75CandlesHeldAfterSignal()).append('\n');
        out.append("Broken levels:         ").append(summary.brokenLevels()).append('\n');
        out.append("Still active levels:   ").append(summary.activeLevels()).append('\n');
        out.append("Breakout events:       ").append(summary.breakoutEvents()).append("\n\n");
        out.append("Strategy:              ").append(result.strategy()).append('\n');
        out.append("Config fingerprint:    ").append(result.configFingerprint()).append('\n');
        out.append("Elapsed:               ").append(result.durationMillis()).append(" ms\n");
        return out.toString();
    }

    public String toJson(BacktestResult result, int minTouches) {
        ObjectNode root = mapper.createObjectNode();
        root.put("symbol", result.symbol());
        root.put("timeframe", result.timeframe());
        root.put("from", result.from().toString());
        root.put("to", result.to().toString());
        root.put("candlesAnalysed", result.candlesAnalysed());
        root.put("durationMillis", result.durationMillis());
        root.put("strategy", result.strategy());
        root.put("configFingerprint", result.configFingerprint());
        root.set("summary", mapper.valueToTree(result.summary(minTouches)));

        ArrayNode signals = root.putArray("signals");
        for (TradingSignal signal : result.signals()) {
            signals.add(signalNode(signal));
        }
        ArrayNode levels = root.putArray("levels");
        for (DetectedLevel level : result.levels()) {
            levels.add(levelNode(level));
        }
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private ObjectNode signalNode(TradingSignal signal) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", signal.id());
        node.put("levelId", signal.levelId());
        node.put("symbol", signal.market().symbol());
        node.put("timeframe", signal.market().timeframe().code());
        node.put("type", signal.type().name());
        node.put("price", signal.price());
        node.put("zoneLower", signal.zoneLower());
        node.put("zoneUpper", signal.zoneUpper());
        node.put("touchCount", signal.touchCount());
        node.put("strength", signal.strength());
        node.put("status", signal.status().name());
        node.put("detectedAt", java.time.Instant.ofEpochMilli(signal.detectedAtTime()).toString());
        ArrayNode touches = node.putArray("touches");
        for (Touch touch : signal.touches()) {
            touches.add(touchNode(touch));
        }
        return node;
    }

    private ObjectNode levelNode(DetectedLevel level) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", level.id());
        node.put("type", level.type().name());
        node.put("price", level.price());
        node.put("zoneLower", level.zoneLower());
        node.put("zoneUpper", level.zoneUpper());
        node.put("status", level.status().name());
        node.put("strength", level.strength());
        node.put("swingCount", level.swingCount());
        node.put("touchCount", level.touchCount());
        node.put("firstSeenAt", java.time.Instant.ofEpochMilli(level.createdAtTime()).toString());
        ArrayNode touches = node.putArray("touches");
        for (Touch touch : level.touches()) {
            touches.add(touchNode(touch));
        }
        level.breakoutEvent().ifPresent(breakout -> {
            ObjectNode b = node.putObject("breakout");
            b.put("confirmedAt", java.time.Instant.ofEpochMilli(breakout.confirmedAtTime()).toString());
            b.put("thresholdPrice", breakout.thresholdPrice());
            b.put("confirmationPrice", breakout.confirmationPrice());
            b.put("touchCountAtBreak", breakout.touchCountAtBreak());
        });
        return node;
    }

    private ObjectNode touchNode(Touch touch) {
        ObjectNode node = mapper.createObjectNode();
        node.put("touchNumber", touch.touchNumber());
        node.put("time", java.time.Instant.ofEpochMilli(touch.extremeTime()).toString());
        node.put("price", touch.extremePrice());
        node.put("distancePercent", touch.distancePercent());
        node.put("candlesSincePrevious", touch.candlesSincePreviousTouch());
        node.put("millisSincePrevious", touch.millisSincePreviousTouch());
        node.put("rejectionPercent", touch.rejectionPercent());
        node.put("closed", touch.closed());
        return node;
    }

    /** One row per signal, with its touches flattened into columns. */
    public String toCsv(BacktestResult result) {
        StringBuilder out = new StringBuilder(4096);
        out.append("signal_id,level_id,symbol,timeframe,type,level_price,zone_lower,zone_upper,")
                .append("touch_count,strength,status,detected_at,")
                .append("touch1_time,touch1_price,touch2_time,touch2_price,touch3_time,touch3_price\n");

        for (TradingSignal signal : result.signals()) {
            out.append(signal.id()).append(',')
                    .append(signal.levelId()).append(',')
                    .append(signal.market().symbol()).append(',')
                    .append(signal.market().timeframe().code()).append(',')
                    .append(signal.type()).append(',')
                    .append(number(signal.price())).append(',')
                    .append(number(signal.zoneLower())).append(',')
                    .append(number(signal.zoneUpper())).append(',')
                    .append(signal.touchCount()).append(',')
                    .append(String.format(Locale.ROOT, "%.4f", signal.strength())).append(',')
                    .append(signal.status()).append(',')
                    .append(java.time.Instant.ofEpochMilli(signal.detectedAtTime()));

            for (int i = 0; i < 3; i++) {
                if (i < signal.touches().size()) {
                    Touch touch = signal.touches().get(i);
                    out.append(',').append(java.time.Instant.ofEpochMilli(touch.extremeTime()))
                            .append(',').append(number(touch.extremePrice()));
                } else {
                    out.append(",,");
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Full precision, no grouping separators: CSV is for machines, not for reading. */
    private static String number(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    public void write(Path path, String content) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to write " + path, ex);
        }
    }
}
