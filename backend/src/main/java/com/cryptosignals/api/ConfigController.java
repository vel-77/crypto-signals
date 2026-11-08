package com.cryptosignals.api;

import com.cryptosignals.analysis.LevelAnalysisEngineFactory;
import com.cryptosignals.config.AnalysisProperties;
import com.cryptosignals.config.ExchangeProperties;
import com.cryptosignals.config.MarketProperties;
import com.cryptosignals.config.NextPotentialProperties;
import com.cryptosignals.config.NotificationProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
@Tag(name = "Configuration", description = "The rules the running engine is using")
public class ConfigController {

    private final AnalysisProperties analysis;
    private final MarketProperties markets;
    private final ExchangeProperties exchange;
    private final NotificationProperties notifications;
    private final LevelAnalysisEngineFactory engineFactory;
    private final NextPotentialProperties nextPotential;

    public ConfigController(AnalysisProperties analysis, MarketProperties markets,
                            ExchangeProperties exchange, NotificationProperties notifications,
                            LevelAnalysisEngineFactory engineFactory,
                            NextPotentialProperties nextPotential) {
        this.analysis = analysis;
        this.markets = markets;
        this.exchange = exchange;
        this.notifications = notifications;
        this.engineFactory = engineFactory;
        this.nextPotential = nextPotential;
    }

    @GetMapping
    @Operation(summary = "Effective configuration",
            description = "Exactly what the engine is running with, including the fingerprint that "
                    + "stored levels and signals are tagged with. Secrets are never included.")
    public Map<String, Object> current() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("analysis", analysis);
        root.put("configFingerprint", analysis.toAnalysisConfig().fingerprint());
        root.put("detectionStrategy", engineFactory.detectionStrategyName());

        Map<String, Object> marketInfo = new LinkedHashMap<>();
        marketInfo.put("symbols", markets.getSymbols());
        marketInfo.put("timeframes", markets.getTimeframes());
        marketInfo.put("lookback", markets.isUnlimitedLookback() ? "unlimited" : markets.getLookback().toString());
        marketInfo.put("watchedMarkets", markets.toMarketKeys().stream().map(Object::toString).toList());
        marketInfo.put("backfillOnStartup", markets.isBackfillOnStartup());
        marketInfo.put("liveEnabled", markets.isLiveEnabled());
        root.put("markets", marketInfo);

        Map<String, Object> nextPotentialInfo = new LinkedHashMap<>();
        nextPotentialInfo.put("minDistancePercent", nextPotential.getMinDistancePercent());
        nextPotentialInfo.put("lookback", nextPotential.getLookback().isZero()
                ? "unlimited" : nextPotential.getLookback().toString());
        root.put("nextPotential", nextPotentialInfo);

        Map<String, Object> exchangeInfo = new LinkedHashMap<>();
        exchangeInfo.put("name", exchange.getName());
        exchangeInfo.put("restBaseUrl", exchange.getRestBaseUrl());
        exchangeInfo.put("wsBaseUrl", exchange.getWsBaseUrl());
        exchangeInfo.put("maxWeightPerMinute", exchange.getMaxWeightPerMinute());
        root.put("exchange", exchangeInfo);

        // Channel availability is useful; the bot token is not, and must never be exposed here.
        Map<String, Object> notificationInfo = new LinkedHashMap<>();
        notificationInfo.put("telegramEnabled", notifications.getTelegram().isUsable());
        notificationInfo.put("notifyHistorical", notifications.isNotifyHistorical());
        notificationInfo.put("maxSignalAge", notifications.getMaxSignalAge().toString());
        root.put("notifications", notificationInfo);

        return root;
    }
}
