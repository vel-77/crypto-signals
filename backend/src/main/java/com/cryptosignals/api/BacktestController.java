package com.cryptosignals.api;

import com.cryptosignals.backtest.BacktestExporter;
import com.cryptosignals.backtest.BacktestRequest;
import com.cryptosignals.backtest.BacktestResult;
import com.cryptosignals.backtest.BacktestService;
import com.cryptosignals.config.AnalysisProperties;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/backtest")
@Tag(name = "Backtest", description = "Replay the detection engine over a historical range")
public class BacktestController {

    private final BacktestService backtestService;
    private final BacktestExporter exporter;
    private final AnalysisProperties analysisProperties;

    public BacktestController(BacktestService backtestService, BacktestExporter exporter,
                              AnalysisProperties analysisProperties) {
        this.backtestService = backtestService;
        this.exporter = exporter;
        this.analysisProperties = analysisProperties;
    }

    @PostMapping
    @Operation(summary = "Run a backtest",
            description = "Runs the live detection engine over stored candles without writing any "
                    + "levels or signals. Returns JSON, CSV or a plain-text report.")
    public ResponseEntity<String> run(
            @Parameter(required = true) @RequestParam String symbol,
            @Parameter(required = true) @RequestParam String timeframe,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "download any candles the database is missing first")
            @RequestParam(defaultValue = "false") boolean fetch,
            @Parameter(description = "json, csv or report") @RequestParam(defaultValue = "json") String format) {

        BacktestRequest request = new BacktestRequest(
                new MarketKey(symbol, Timeframe.fromCode(timeframe)),
                from, to != null ? to : Instant.now(),
                analysisProperties.toAnalysisConfig(), fetch);

        BacktestResult result = backtestService.run(request);
        int minTouches = analysisProperties.getMinTouches();

        return switch (format.toLowerCase()) {
            case "csv" -> ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"backtest-" + symbol + "-" + timeframe + ".csv\"")
                    .body(exporter.toCsv(result));
            case "report" -> ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(exporter.toReport(result, minTouches));
            default -> ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(exporter.toJson(result, minTouches));
        };
    }
}
