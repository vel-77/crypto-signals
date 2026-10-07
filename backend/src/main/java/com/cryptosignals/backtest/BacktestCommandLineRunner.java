package com.cryptosignals.backtest;

import com.cryptosignals.config.AnalysisProperties;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Command-line backtesting.
 *
 * <pre>
 * java -jar crypto-signals.jar --backtest \
 *      --symbol=BTCUSDT --timeframe=5m \
 *      --from=2025-01-01 --to=2026-09-20 \
 *      --fetch --format=json --out=./signals.json
 * </pre>
 *
 * <p>When {@code --backtest} is present the application runs the analysis, writes its report and
 * exits; live streaming and notifications never start. That keeps a validation run completely
 * side-effect free apart from any candles it was asked to download.
 */
@Component
@Order(1)
public class BacktestCommandLineRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestCommandLineRunner.class);

    public static final String FLAG = "backtest";

    private final BacktestService backtestService;
    private final BacktestExporter exporter;
    private final AnalysisProperties analysisProperties;
    private final ConfigurableApplicationContext context;

    public BacktestCommandLineRunner(BacktestService backtestService, BacktestExporter exporter,
                                     AnalysisProperties analysisProperties,
                                     ConfigurableApplicationContext context) {
        this.backtestService = backtestService;
        this.exporter = exporter;
        this.analysisProperties = analysisProperties;
        this.context = context;
    }

    /** True when the process was started to run a backtest rather than to serve traffic. */
    public static boolean isBacktestRun(ApplicationArguments args) {
        return args.containsOption(FLAG);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isBacktestRun(args)) {
            return;
        }
        int exitCode = 0;
        try {
            Timeframe timeframe = Timeframe.fromCode(option(args, "timeframe", "15m"));
            BacktestRequest request = new BacktestRequest(
                    new MarketKey(required(args, "symbol"), timeframe),
                    parseInstant(required(args, "from")),
                    parseInstant(option(args, "to", Instant.now().toString())),
                    analysisProperties.toAnalysisConfig(timeframe),
                    args.containsOption("fetch"));

            BacktestResult result = backtestService.run(request);
            int minTouches = analysisProperties.getMinTouches();

            System.out.println();
            System.out.println(exporter.toReport(result, minTouches));

            String out = option(args, "out", null);
            if (out != null) {
                String format = option(args, "format", out.endsWith(".csv") ? "csv" : "json");
                String content = "csv".equalsIgnoreCase(format)
                        ? exporter.toCsv(result)
                        : exporter.toJson(result, minTouches);
                exporter.write(Path.of(out), content);
                System.out.println("Exported " + result.signals().size()
                        + " signal(s) as " + format + " to " + out);
            }
        } catch (Exception ex) {
            log.error("Backtest failed: {}", ex.getMessage(), ex);
            System.err.println("Backtest failed: " + ex.getMessage());
            exitCode = 1;
        }
        final int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    private static String required(ApplicationArguments args, String name) {
        String value = option(args, name, null);
        if (value == null) {
            throw new IllegalArgumentException("--" + name + " is required for a backtest run");
        }
        return value;
    }

    private static String option(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }

    /** Accepts either a plain date ({@code 2025-01-01}) or a full ISO instant. */
    static Instant parseInstant(String value) {
        String trimmed = value.trim();
        if (trimmed.length() == 10) {
            return LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return Instant.parse(trimmed);
    }
}
