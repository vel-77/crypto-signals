package com.cryptosignals.marketdata;

import com.cryptosignals.backtest.BacktestCommandLineRunner;
import com.cryptosignals.config.MarketProperties;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.exchange.ExchangeClient;
import com.cryptosignals.notifications.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Startup sequence and ongoing reconciliation.
 *
 * <p>Runs on a background thread so the HTTP API and health endpoint come up immediately: a first
 * backfill of a year of one-minute candles across several markets takes minutes, and a container
 * orchestrator that health-checks during that window must not conclude the app is dead.
 *
 * <p>Per market: backfill history, repair any holes, replay it through a fresh engine, then hand
 * over to the live stream. Each step is idempotent, so a crash at any point is recovered by simply
 * starting again.
 */
@Component
public class MarketDataBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketDataBootstrap.class);

    public enum State { PENDING, RUNNING, READY, FAILED }

    private final MarketProperties properties;
    private final ExchangeClient exchange;
    private final MarketDataService marketData;
    private final MarketAnalysisService analysisService;
    private final LiveStreamManager liveStream;
    private final NotificationService notifications;

    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
    private volatile String lastError;

    public MarketDataBootstrap(MarketProperties properties, ExchangeClient exchange,
                               MarketDataService marketData, MarketAnalysisService analysisService,
                               LiveStreamManager liveStream, NotificationService notifications) {
        this.properties = properties;
        this.exchange = exchange;
        this.marketData = marketData;
        this.analysisService = analysisService;
        this.liveStream = liveStream;
        this.notifications = notifications;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (BacktestCommandLineRunner.isBacktestRun(args)) {
            log.info("Backtest run: skipping backfill, replay and live streaming");
            return;
        }
        Thread thread = new Thread(this::bootstrap, "market-data-bootstrap");
        thread.setDaemon(true);
        thread.start();
    }

    private void bootstrap() {
        state.set(State.RUNNING);
        try {
            List<MarketKey> ready = new ArrayList<>();
            for (MarketKey key : properties.toMarketKeys()) {
                try {
                    if (!exchange.supportsSymbol(key.symbol())) {
                        log.warn("Skipping {}: the exchange does not list this symbol", key);
                        continue;
                    }
                    MarketEngine engine = analysisService.initialise(key);
                    if (properties.isBackfillOnStartup()) {
                        marketData.backfill(engine.market(), key);
                        marketData.healGaps(engine.market(), key);
                    }
                    analysisService.replayHistory(engine);
                    analysisService.reconcileStoredLevels(engine);
                    analysisService.fillNextPotential(engine);
                    ready.add(key);
                } catch (Exception ex) {
                    // One unavailable or misconfigured market must not stop the others.
                    log.error("Bootstrap failed for {}: {}", key, ex.toString(), ex);
                }
            }

            // Signals found during replay are persisted undelivered. Resolve them now rather than
            // leaving them for the retry sweeper: with historical notifications switched off this
            // simply marks them suppressed, and with them switched on the operator gets their
            // backlog immediately instead of up to a retry interval later.
            notifications.retryPending();

            if (properties.isLiveEnabled() && !ready.isEmpty()) {
                liveStream.start(ready);
            }
            state.set(State.READY);
            log.info("Bootstrap complete: {} market(s) live", ready.size());
            analysisService.fillNextPotentialOfUnwatchedMarkets();
        } catch (Exception ex) {
            lastError = ex.toString();
            state.set(State.FAILED);
            log.error("Bootstrap failed: {}", ex.toString(), ex);
        }
    }

    /**
     * Periodic safety net.
     *
     * <p>The live stream can miss candles without ever reporting an error — a silent half-open
     * socket, a brief outage that reconnected cleanly, an exchange that simply skipped a minute.
     * This sweep refetches anything missing and then feeds every stored-but-unanalysed candle through
     * the engine, so the analysis converges back on the truth even when nothing looked broken.
     */
    @Scheduled(initialDelayString = "${crypto-signals.markets.reconciliation-interval:15m}",
            fixedDelayString = "${crypto-signals.markets.reconciliation-interval:15m}")
    public void reconcile() {
        if (state.get() != State.READY) {
            return;
        }
        for (MarketEngine engine : analysisService.allEngines()) {
            try {
                int fetched = marketData.backfill(engine.market(), engine.key());
                int repaired = marketData.healGaps(engine.market(), engine.key());
                if (fetched > 0 || repaired > 0) {
                    log.info("Reconciliation for {}: {} new, {} repaired",
                            engine.key(), fetched, repaired);
                }
            } catch (Exception ex) {
                log.warn("Reconciliation failed for {}: {}", engine.key(), ex.toString());
            }
            // Separate from the download above: even if the exchange is unreachable right now,
            // candles stored earlier must still reach the engine.
            try {
                analysisService.catchUp(engine);
                analysisService.reconcileStoredLevels(engine);
                analysisService.fillNextPotential(engine);
            } catch (Exception ex) {
                log.warn("Catch-up failed for {}: {}", engine.key(), ex.toString());
            }
        }
    }

    public State state() {
        return state.get();
    }

    public String lastError() {
        return lastError;
    }
}
