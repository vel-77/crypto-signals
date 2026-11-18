package com.cryptosignals.api;

import com.cryptosignals.config.Venue;
import com.cryptosignals.config.Venues;
import com.cryptosignals.marketdata.LiveStreamManager;
import com.cryptosignals.marketdata.MarketAnalysisService;
import com.cryptosignals.marketdata.MarketDataBootstrap;
import com.cryptosignals.marketdata.MarketEngine;
import com.cryptosignals.persistence.jdbc.CandleJdbcRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/status")
@Tag(name = "Status", description = "Runtime health of the ingestion and analysis pipeline")
public class StatusController {

    private final MarketDataBootstrap bootstrap;
    private final LiveStreamManager liveStream;
    private final MarketAnalysisService analysisService;
    private final CandleJdbcRepository candles;
    private final Venues venues;

    public StatusController(MarketDataBootstrap bootstrap, LiveStreamManager liveStream,
                            MarketAnalysisService analysisService, CandleJdbcRepository candles,
                            Venues venues) {
        this.bootstrap = bootstrap;
        this.liveStream = liveStream;
        this.analysisService = analysisService;
        this.candles = candles;
        this.venues = venues;
    }

    @GetMapping
    @Operation(summary = "Pipeline status",
            description = "For one market (exchange), the first running one by default: whether "
                    + "startup backfill has finished, whether the live stream is connected, and how "
                    + "far each coin and timeframe has been analysed. `exchanges` sums up every "
                    + "running market.")
    public Map<String, Object> status(
            @Parameter(description = "market: BINANCE_FUTURES or BINANCE; default the first one running")
            @RequestParam(required = false) String exchange) {
        Venue venue = venues.resolve(exchange);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("exchange", venue.name());
        root.put("label", venue.label());
        root.put("bootstrap", bootstrap.state(venue).name());
        root.put("bootstrapError", bootstrap.lastError(venue));
        root.put("liveStream", stream(venue));

        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Venue each : venues.all()) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("exchange", each.name());
            summary.put("label", each.label());
            summary.put("bootstrap", bootstrap.state(each).name());
            summary.put("streamConnected", liveStream.isConnected(each));
            summary.put("markets", analysisService.engines(each.name()).size());
            summaries.add(summary);
        }
        root.put("exchanges", summaries);

        List<Map<String, Object>> marketStatuses = new ArrayList<>();
        for (MarketEngine engine : analysisService.engines(venue.name())) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("market", engine.key().toString());
            entry.put("historicalReplay", engine.isHistorical());
            long last = engine.lastCandleOpenTime();
            entry.put("lastAnalysedCandle", last > 0 ? Instant.ofEpochMilli(last) : null);
            entry.put("candlesStored", candles.count(engine.market().getId()));
            entry.put("candlesAnalysed", engine.unsafeEngine().candlesProcessed());
            entry.put("gapsSeen", engine.unsafeEngine().detectedGaps());
            entry.put("duplicatesIgnored", engine.unsafeEngine().duplicateCandles());
            entry.put("activeLevels", engine.unsafeEngine().activeLevels().size());
            marketStatuses.add(entry);
        }
        root.put("markets", marketStatuses);
        return root;
    }

    private Map<String, Object> stream(Venue venue) {
        Map<String, Object> stream = new LinkedHashMap<>();
        stream.put("running", liveStream.isRunning(venue));
        stream.put("connected", liveStream.isConnected(venue));
        stream.put("reconnects", liveStream.reconnectCount(venue));
        stream.put("closedCandlesReceived", liveStream.closedCandlesReceived(venue));
        stream.put("partialCandlesDropped", liveStream.partialCandlesDropped(venue));
        stream.put("lastCandleAt", liveStream.lastCandleAt(venue));
        return stream;
    }
}
