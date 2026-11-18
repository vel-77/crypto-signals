package com.cryptosignals.marketdata;

import com.cryptosignals.backtest.BacktestCommandLineRunner;
import com.cryptosignals.config.MarketProperties;
import com.cryptosignals.config.Venue;
import com.cryptosignals.config.Venues;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.notifications.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
 *
 * <p>The markets are those of each venue's {@link WatchList}. When one changes while running,
 * markets are started and stopped to match without a restart.
 *
 * <p>Each venue (spot, futures) starts on its own thread: they have separate request budgets and
 * separate live streams, so neither waits for the other, and one that cannot start does not hold
 * the other back.
 */
@Component
public class MarketDataBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketDataBootstrap.class);

    public enum State { PENDING, RUNNING, READY, FAILED }

    private final MarketProperties properties;
    private final Venues venues;
    private final MarketDataService marketData;
    private final MarketAnalysisService analysisService;
    private final LiveStreamManager liveStream;
    private final NotificationService notifications;
    private final WatchLists watchLists;

    private final Map<String, AtomicReference<State>> states = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();

    public MarketDataBootstrap(MarketProperties properties, Venues venues,
                               MarketDataService marketData, MarketAnalysisService analysisService,
                               LiveStreamManager liveStream, NotificationService notifications,
                               WatchLists watchLists) {
        this.properties = properties;
        this.venues = venues;
        this.marketData = marketData;
        this.analysisService = analysisService;
        this.liveStream = liveStream;
        this.notifications = notifications;
        this.watchLists = watchLists;
        for (Venue venue : venues.all()) {
            states.put(venue.name(), new AtomicReference<>(State.PENDING));
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        if (BacktestCommandLineRunner.isBacktestRun(args)) {
            log.info("Backtest run: skipping backfill, replay and live streaming");
            return;
        }
        for (Venue venue : venues.all()) {
            Thread thread = new Thread(() -> bootstrap(venue),
                    "bootstrap-" + venue.name().toLowerCase(Locale.ROOT).replace('_', '-'));
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void bootstrap(Venue venue) {
        AtomicReference<State> state = states.get(venue.name());
        state.set(State.RUNNING);
        try {
            // The lists are chosen together: the first market to get here does it for all.
            watchLists.initialiseOnce();
            WatchList watchList = watchLists.of(venue);
            List<MarketKey> ready = new ArrayList<>();
            for (MarketKey key : watchList.marketKeys()) {
                if (startMarket(venue, key)) {
                    ready.add(key);
                }
            }

            // Signals found during replay are persisted undelivered. Resolve them now rather than
            // leaving them for the retry sweeper: with historical notifications switched off this
            // simply marks them suppressed, and with them switched on the operator gets their
            // backlog immediately instead of up to a retry interval later.
            notifications.retryPending();
            retireUnwatchedMarkets(watchList);

            if (properties.isLiveEnabled() && !ready.isEmpty()) {
                liveStream.start(venue, ready);
            }
            // While live candles already flow: usually nothing is missing and this is quick.
            analysisService.fillNextPotentialOfUnwatchedMarkets(venue);
            state.set(State.READY);
            log.info("Bootstrap complete on {}: {} market(s) live", venue.label(), ready.size());
        } catch (Exception ex) {
            errors.put(venue.name(), ex.toString());
            state.set(State.FAILED);
            log.error("Bootstrap failed on {}: {}", venue.label(), ex.toString(), ex);
        }
    }

    /**
     * Brings one market up: backfill history, repair any holes, replay it through a fresh engine,
     * retire stored levels it no longer tracks, and fill in next potential lows / highs.
     *
     * @return whether the market is ready for live candles. One unavailable or misconfigured
     *         market must not stop the others, so failures are logged, not thrown.
     */
    private boolean startMarket(Venue venue, MarketKey key) {
        try {
            if (!venue.client().supportsSymbol(key.symbol())) {
                log.warn("Skipping {} on {}: the exchange does not list this symbol", key, venue.label());
                return false;
            }
            MarketEngine engine = analysisService.initialise(venue, key);
            if (properties.isBackfillOnStartup()) {
                marketData.backfill(venue.client(), engine.market(), key);
                marketData.healGaps(venue.client(), engine.market(), key);
            }
            analysisService.replayHistory(engine);
            analysisService.reconcileStoredLevels(engine);
            analysisService.fillNextPotential(engine);
            return true;
        } catch (Exception ex) {
            log.error("Bootstrap failed for {} on {}: {}", key, venue.label(), ex.toString(), ex);
            // A half-replayed engine must not be picked up by a later attempt.
            analysisService.discard(venue.name(), key);
            return false;
        }
    }

    /**
     * Refreshes the watch lists once one is due (daily by default, sooner after a failed attempt)
     * and starts and stops markets to match on each venue that is running.
     *
     * <p>The lists are chosen together, so not while a venue is still starting: its markets would
     * not follow the change.
     */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void refreshWatchList() {
        boolean starting = venues.all().stream().map(this::state)
                .anyMatch(s -> s == State.PENDING || s == State.RUNNING);
        if (starting || !watchLists.isDue(Instant.now())) {
            return;
        }
        watchLists.refresh().forEach((watchList, change) -> {
            Venue venue = watchList.venue();
            if (change.isPresent() && state(venue) == State.READY) {
                applyWatchList(venue);
            }
        });
    }

    /**
     * Makes a venue's running markets match its watch list: starts markets it gained, and markets
     * that failed to start before; stops markets it lost and retires their stored levels; then
     * restarts the venue's live stream if anything changed.
     *
     * <p>New markets are brought up first, while the live stream keeps serving the others, which
     * takes a few seconds per market.
     */
    public synchronized void applyWatchList(Venue venue) {
        WatchList watchList = watchLists.of(venue);
        Set<MarketKey> wanted = new LinkedHashSet<>(watchList.marketKeys());

        List<MarketKey> started = new ArrayList<>();
        for (MarketKey key : wanted) {
            if (analysisService.engine(venue.name(), key).isEmpty() && startMarket(venue, key)) {
                started.add(key);
            }
        }
        List<MarketKey> stopped = new ArrayList<>();
        for (MarketEngine engine : List.copyOf(analysisService.engines(venue.name()))) {
            if (!wanted.contains(engine.key())) {
                analysisService.discard(venue.name(), engine.key());
                stopped.add(engine.key());
            }
        }
        if (!started.isEmpty()) {
            notifications.retryPending();
        }
        retireUnwatchedMarkets(watchList);

        if (started.isEmpty() && stopped.isEmpty()) {
            return;
        }
        List<MarketKey> running = wanted.stream()
                .filter(key -> analysisService.engine(venue.name(), key).isPresent())
                .toList();
        if (properties.isLiveEnabled()) {
            liveStream.restart(venue, running);
        }
        log.info("Watch list applied on {}: {} market(s) started, {} stopped; {} market(s) running",
                venue.label(), started.size(), stopped.size(), running.size());
    }

    private void retireUnwatchedMarkets(WatchList watchList) {
        if (!watchList.isSettled()) {
            return;
        }
        try {
            analysisService.retireUnwatchedMarkets(watchList.venue().name(), new HashSet<>(watchList.symbols()),
                    properties.watchedTimeframes());
        } catch (Exception ex) {
            log.warn("Could not retire the levels of unwatched markets on {}: {}",
                    watchList.venue().label(), ex.toString());
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
        for (Venue venue : venues.all()) {
            if (state(venue) != State.READY) {
                continue;
            }
            for (MarketEngine engine : List.copyOf(analysisService.engines(venue.name()))) {
                try {
                    int fetched = marketData.backfill(venue.client(), engine.market(), engine.key());
                    int repaired = marketData.healGaps(venue.client(), engine.market(), engine.key());
                    if (fetched > 0 || repaired > 0) {
                        log.info("Reconciliation for {}: {} new, {} repaired",
                                engine.describe(), fetched, repaired);
                    }
                } catch (Exception ex) {
                    log.warn("Reconciliation failed for {}: {}", engine.describe(), ex.toString());
                }
                // Separate from the download above: even if the exchange is unreachable right now,
                // candles stored earlier must still reach the engine.
                try {
                    analysisService.catchUp(engine);
                    analysisService.reconcileStoredLevels(engine);
                    analysisService.fillNextPotential(engine);
                } catch (Exception ex) {
                    log.warn("Catch-up failed for {}: {}", engine.describe(), ex.toString());
                }
            }
        }
    }

    /**
     * All venues together: READY once every one is, FAILED if any failed, RUNNING while any is
     * still starting.
     */
    public State state() {
        List<State> all = venues.all().stream().map(this::state).toList();
        if (all.stream().allMatch(s -> s == State.READY)) {
            return State.READY;
        }
        if (all.contains(State.FAILED)) {
            return State.FAILED;
        }
        return all.contains(State.RUNNING) ? State.RUNNING : State.PENDING;
    }

    public State state(Venue venue) {
        AtomicReference<State> state = states.get(venue.name());
        return state == null ? State.PENDING : state.get();
    }

    /** Why a venue failed to start, for each one that did; null when none did. */
    public String lastError() {
        String all = venues.all().stream()
                .filter(v -> errors.containsKey(v.name()))
                .map(v -> v.name() + ": " + errors.get(v.name()))
                .collect(Collectors.joining("; "));
        return all.isEmpty() ? null : all;
    }

    public String lastError(Venue venue) {
        return errors.get(venue.name());
    }
}
