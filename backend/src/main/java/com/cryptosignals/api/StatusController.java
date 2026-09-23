package com.cryptosignals.api;

import com.cryptosignals.marketdata.LiveStreamManager;
import com.cryptosignals.marketdata.MarketAnalysisService;
import com.cryptosignals.marketdata.MarketDataBootstrap;
import com.cryptosignals.marketdata.MarketEngine;
import com.cryptosignals.persistence.jdbc.CandleJdbcRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public StatusController(MarketDataBootstrap bootstrap, LiveStreamManager liveStream,
                            MarketAnalysisService analysisService, CandleJdbcRepository candles) {
        this.bootstrap = bootstrap;
        this.liveStream = liveStream;
        this.analysisService = analysisService;
        this.candles = candles;
    }

    @GetMapping
    @Operation(summary = "Pipeline status",
            description = "Whether startup backfill has finished, whether the live stream is "
                    + "connected, and how far each market has been analysed.")
    public Map<String, Object> status() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("bootstrap", bootstrap.state().name());
        root.put("bootstrapError", bootstrap.lastError());

        Map<String, Object> stream = new LinkedHashMap<>();
        stream.put("running", liveStream.isRunning());
        stream.put("connected", liveStream.isConnected());
        stream.put("reconnects", liveStream.reconnectCount());
        stream.put("closedCandlesReceived", liveStream.closedCandlesReceived());
        stream.put("partialCandlesDropped", liveStream.partialCandlesDropped());
        stream.put("lastCandleAt", liveStream.lastCandleAt());
        root.put("liveStream", stream);

        List<Map<String, Object>> marketStatuses = new ArrayList<>();
        for (MarketEngine engine : analysisService.allEngines()) {
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
}
