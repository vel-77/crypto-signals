package com.cryptosignals.api;

import com.cryptosignals.analysis.LevelAnalysisEngineFactory;
import com.cryptosignals.config.AnalysisProperties;
import com.cryptosignals.config.MarketProperties;
import com.cryptosignals.config.NextPotentialProperties;
import com.cryptosignals.config.NotificationProperties;
import com.cryptosignals.config.Venue;
import com.cryptosignals.config.Venues;
import com.cryptosignals.marketdata.WatchList;
import com.cryptosignals.marketdata.WatchLists;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
@Tag(name = "Configuration", description = "The rules the running engine is using")
public class ConfigController {

    private final AnalysisProperties analysis;
    private final MarketProperties markets;
    private final Venues venues;
    private final NotificationProperties notifications;
    private final LevelAnalysisEngineFactory engineFactory;
    private final NextPotentialProperties nextPotential;
    private final WatchLists watchLists;

    public ConfigController(AnalysisProperties analysis, MarketProperties markets, Venues venues,
                            NotificationProperties notifications, LevelAnalysisEngineFactory engineFactory,
                            NextPotentialProperties nextPotential, WatchLists watchLists) {
        this.analysis = analysis;
        this.markets = markets;
        this.venues = venues;
        this.notifications = notifications;
        this.engineFactory = engineFactory;
        this.nextPotential = nextPotential;
        this.watchLists = watchLists;
    }

    @GetMapping
    @Operation(summary = "Effective configuration",
            description = "Exactly what the engine is running with on one market (exchange), the first "
                    + "running one by default, including the fingerprint that stored levels and "
                    + "signals are tagged with. `exchanges` lists every running market, the default "
                    + "first. Secrets are never included.")
    public Map<String, Object> current(
            @Parameter(description = "market: BINANCE_FUTURES or BINANCE; default the first one running")
            @RequestParam(required = false) String exchange) {
        WatchList watchList = watchLists.resolve(exchange);
        Venue venue = watchList.venue();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("analysis", analysis);
        root.put("configFingerprint", analysis.toAnalysisConfig().fingerprint());
        root.put("detectionStrategy", engineFactory.detectionStrategyName());

        Map<String, Object> marketInfo = new LinkedHashMap<>();
        // The symbols watched right now: with volume selection on, they change while running.
        // GET /api/watchlist says why each one is there.
        marketInfo.put("symbols", watchList.symbols());
        marketInfo.put("fixedSymbols", watchList.fixedSymbols());
        marketInfo.put("timeframes", markets.getTimeframes());
        marketInfo.put("lookback", markets.isUnlimitedLookback() ? "unlimited" : markets.getLookback().toString());
        marketInfo.put("watchedMarkets", watchList.marketKeys().stream().map(Object::toString).toList());
        MarketProperties.VolumeSelection selection = markets.getVolumeSelection();
        Map<String, Object> selectionInfo = new LinkedHashMap<>();
        selectionInfo.put("enabled", selection.isEnabled());
        if (selection.isEnabled()) {
            selectionInfo.put("quoteAsset", selection.getQuoteAsset());
            selectionInfo.put("minDailyVolume", watchList.minDailyVolume());
            selectionInfo.put("window", selection.getWindow().toString());
            selectionInfo.put("maxSymbols", selection.getMaxSymbols());
            selectionInfo.put("keepPercent", selection.getKeepPercent());
            selectionInfo.put("minPriceRangePercent", selection.getMinPriceRangePercent());
            selectionInfo.put("excludeTags", selection.getExcludeTags());
            selectionInfo.put("excludeAssets", selection.getExcludeAssets());
            selectionInfo.put("refreshInterval", selection.getRefreshInterval().toString());
        }
        marketInfo.put("volumeSelection", selectionInfo);
        marketInfo.put("backfillOnStartup", markets.isBackfillOnStartup());
        marketInfo.put("liveEnabled", markets.isLiveEnabled());
        root.put("markets", marketInfo);

        Map<String, Object> nextPotentialInfo = new LinkedHashMap<>();
        nextPotentialInfo.put("minDistancePercent", nextPotential.getMinDistancePercent());
        nextPotentialInfo.put("lookback", nextPotential.getLookback().isZero()
                ? "unlimited" : nextPotential.getLookback().toString());
        root.put("nextPotential", nextPotentialInfo);

        Map<String, Object> exchangeInfo = new LinkedHashMap<>();
        exchangeInfo.put("name", venue.name());
        exchangeInfo.put("label", venue.label());
        exchangeInfo.put("restBaseUrl", venue.settings().restBaseUrl());
        exchangeInfo.put("wsBaseUrl", venue.settings().wsBaseUrl());
        exchangeInfo.put("maxWeightPerMinute", venue.settings().maxWeightPerMinute());
        root.put("exchange", exchangeInfo);
        List<Map<String, Object>> running = venues.all().stream()
                .map(v -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", v.name());
                    entry.put("label", v.label());
                    entry.put("minDailyVolume", watchLists.of(v).minDailyVolume());
                    return entry;
                })
                .toList();
        root.put("exchanges", running);

        // Channel availability is useful; the bot token is not, and must never be exposed here.
        Map<String, Object> notificationInfo = new LinkedHashMap<>();
        notificationInfo.put("telegramEnabled", notifications.getTelegram().isUsable());
        notificationInfo.put("notifyHistorical", notifications.isNotifyHistorical());
        notificationInfo.put("maxSignalAge", notifications.getMaxSignalAge().toString());
        root.put("notifications", notificationInfo);

        return root;
    }
}
