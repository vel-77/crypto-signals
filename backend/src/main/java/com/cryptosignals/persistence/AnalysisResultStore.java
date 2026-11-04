package com.cryptosignals.persistence;

import com.cryptosignals.analysis.engine.EngineUpdate;
import com.cryptosignals.analysis.model.BreakoutEvent;
import com.cryptosignals.analysis.model.DetectedLevel;
import com.cryptosignals.analysis.model.Touch;
import com.cryptosignals.analysis.model.TradingSignal;
import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.SignalStatus;
import com.cryptosignals.persistence.entity.BreakoutEventEntity;
import com.cryptosignals.persistence.entity.LevelTouchEntity;
import com.cryptosignals.persistence.entity.MarketEntity;
import com.cryptosignals.persistence.entity.PriceLevelEntity;
import com.cryptosignals.persistence.entity.SignalEntity;
import com.cryptosignals.persistence.repository.BreakoutEventRepository;
import com.cryptosignals.persistence.repository.LevelTouchRepository;
import com.cryptosignals.persistence.repository.MarketRepository;
import com.cryptosignals.persistence.repository.PriceLevelRepository;
import com.cryptosignals.persistence.repository.SignalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Writes engine output to the database.
 *
 * <p>Every write is an upsert keyed on the engine's deterministic ids, so replaying a range — after
 * a restart, after a re-backfill, or because two overlapping fetches covered the same candles —
 * converges on the same rows instead of duplicating them. That is what lets the system treat
 * persisted state as a cache of a pure function rather than as something that must be carefully
 * mutated in step with the engine.
 */
@Service
public class AnalysisResultStore {

    private static final Logger log = LoggerFactory.getLogger(AnalysisResultStore.class);

    /** Ids per bulk update, well inside the database's bind-parameter limit. */
    private static final int RECONCILE_BATCH = 1_000;

    private final MarketRepository markets;
    private final PriceLevelRepository levels;
    private final LevelTouchRepository touches;
    private final BreakoutEventRepository breakouts;
    private final SignalRepository signals;

    public AnalysisResultStore(MarketRepository markets, PriceLevelRepository levels,
                               LevelTouchRepository touches, BreakoutEventRepository breakouts,
                               SignalRepository signals) {
        this.markets = markets;
        this.levels = levels;
        this.touches = touches;
        this.breakouts = breakouts;
        this.signals = signals;
    }

    /** Finds the market row, creating it on first sight. */
    @Transactional
    public MarketEntity resolveMarket(String exchange, MarketKey key) {
        return markets.findByExchangeAndSymbolAndTimeframe(exchange, key.symbol(), key.timeframe().code())
                .orElseGet(() -> markets.save(
                        new MarketEntity(exchange, key.symbol(), key.timeframe().code())));
    }

    /** Applies everything one candle changed. */
    @Transactional
    public void apply(MarketEntity market, EngineUpdate update, String strategy, String fingerprint) {
        if (!update.accepted() || !update.hasChanges()) {
            return;
        }
        for (DetectedLevel level : update.changedLevels()) {
            saveLevel(market, level, strategy, fingerprint);
        }
        for (BreakoutEvent breakout : update.newBreakouts()) {
            saveBreakout(breakout);
        }
        for (TradingSignal signal : update.newSignals()) {
            saveSignal(market, signal);
        }
        for (TradingSignal changed : update.statusChangedSignals()) {
            signals.findById(UUID.fromString(changed.id())).ifPresent(entity -> {
                entity.setStatus(changed.status());
                signals.save(entity);
            });
        }
    }

    private void saveLevel(MarketEntity market, DetectedLevel level, String strategy, String fingerprint) {
        UUID id = UUID.fromString(level.id());
        PriceLevelEntity entity = levels.findById(id).orElseGet(() -> new PriceLevelEntity(id));

        entity.setMarketId(market.getId());
        entity.setSymbol(level.market().symbol());
        entity.setTimeframe(level.market().timeframe().code());
        entity.setType(level.type());
        entity.setPrice(decimal(level.price()));
        entity.setZoneLower(decimal(level.zoneLower()));
        entity.setZoneUpper(decimal(level.zoneUpper()));
        entity.setStrength(level.strength());
        entity.setStatus(level.status());
        entity.setSwingCount(level.swingCount());
        entity.setTouchCount(level.touchCount());
        entity.setCreatedAtIndex(level.createdAtIndex());
        entity.setFirstSeenAt(Instant.ofEpochMilli(level.createdAtTime()));
        entity.setLastTouchAt(level.lastTouchTime() > 0 ? Instant.ofEpochMilli(level.lastTouchTime()) : null);
        entity.setSupersededBy(level.supersededBy() == null ? null : UUID.fromString(level.supersededBy()));
        entity.setSupersededByPrice(Double.isNaN(level.supersededByPrice()) ? null : decimal(level.supersededByPrice()));
        entity.setSupersededAt(level.supersededAtTime() > 0 ? Instant.ofEpochMilli(level.supersededAtTime()) : null);
        entity.setSupersededReason(level.supersedeReason());
        entity.setStrategy(strategy);
        entity.setConfigFingerprint(fingerprint);
        levels.save(entity);

        saveTouches(id, level.touches());
    }

    /**
     * Reconciles a level's touches.
     *
     * <p>Existing rows are updated in place rather than deleted and re-inserted: the last touch is
     * mutable while it is still open (its extreme and its rejection distance keep moving), and
     * stable touch ids make the API's touch listing stable for clients.
     *
     * <p>Rows numbered beyond what the engine now reports are removed. They only exist when the
     * same level was analysed under different rules before — for instance it collected a fourth
     * touch under old rules but is now superseded at its third.
     */
    private void saveTouches(UUID levelId, List<Touch> current) {
        Map<Integer, LevelTouchEntity> existing = new HashMap<>();
        List<LevelTouchEntity> stale = new ArrayList<>();
        for (LevelTouchEntity entity : touches.findByLevelIdOrderByTouchNumberAsc(levelId)) {
            if (entity.getTouchNumber() > current.size()) {
                stale.add(entity);
            } else {
                existing.put(entity.getTouchNumber(), entity);
            }
        }
        if (!stale.isEmpty()) {
            touches.deleteAll(stale);
        }

        for (Touch touch : current) {
            LevelTouchEntity entity = existing.get(touch.touchNumber());
            if (entity == null) {
                entity = new LevelTouchEntity();
                entity.setLevelId(levelId);
                entity.setTouchNumber(touch.touchNumber());
            }
            entity.setTouchAt(Instant.ofEpochMilli(touch.extremeTime()));
            entity.setStartedAt(Instant.ofEpochMilli(touch.startTime()));
            entity.setEndedAt(Instant.ofEpochMilli(touch.endTime()));
            entity.setCandleOpenTime(Instant.ofEpochMilli(touch.extremeTime()));
            entity.setPrice(decimal(touch.extremePrice()));
            entity.setLevelPriceAtTouch(decimal(touch.levelPriceAtTouch()));
            entity.setDistanceFromLevel(decimal(touch.distanceFromLevel()));
            entity.setDistancePercent(touch.distancePercent());
            entity.setCandlesSincePrevious(touch.candlesSincePreviousTouch());
            entity.setMillisSincePrevious(touch.millisSincePreviousTouch());
            entity.setPriceDeltaFromPrevious(decimal(touch.priceDeltaFromPreviousTouch()));
            entity.setRejectionPercent(touch.rejectionPercent());
            entity.setClosed(touch.closed());
            entity.setStartIndex(touch.startIndex());
            entity.setEndIndex(touch.endIndex());
            entity.setExtremeIndex(touch.extremeIndex());
            touches.save(entity);
        }
    }

    private void saveBreakout(BreakoutEvent breakout) {
        UUID levelId = UUID.fromString(breakout.levelId());
        if (breakouts.findByLevelId(levelId).isPresent()) {
            return;
        }
        BreakoutEventEntity entity = new BreakoutEventEntity();
        entity.setLevelId(levelId);
        entity.setLevelType(breakout.levelType());
        entity.setLevelPrice(decimal(breakout.levelPrice()));
        entity.setThresholdPrice(decimal(breakout.thresholdPrice()));
        entity.setConfirmationPrice(decimal(breakout.confirmationPrice()));
        entity.setFirstBreachIndex(breakout.firstBreachIndex());
        entity.setConfirmedAtIndex(breakout.confirmedAtIndex());
        entity.setConfirmedAt(Instant.ofEpochMilli(breakout.confirmedAtTime()));
        entity.setConfirmationCandles(breakout.confirmationCandles());
        entity.setTouchCountAtBreak(breakout.touchCountAtBreak());
        breakouts.save(entity);
        log.info("Level {} broken at {} (threshold {})", breakout.levelId(),
                breakout.confirmationPrice(), breakout.thresholdPrice());
    }

    private void saveSignal(MarketEntity market, TradingSignal signal) {
        UUID id = UUID.fromString(signal.id());
        if (signals.existsById(id)) {
            return;
        }
        SignalEntity entity = new SignalEntity(id);
        entity.setLevelId(UUID.fromString(signal.levelId()));
        entity.setMarketId(market.getId());
        entity.setSymbol(signal.market().symbol());
        entity.setTimeframe(signal.market().timeframe().code());
        entity.setType(signal.type());
        entity.setPrice(decimal(signal.price()));
        entity.setZoneLower(decimal(signal.zoneLower()));
        entity.setZoneUpper(decimal(signal.zoneUpper()));
        entity.setTouchCount(signal.touchCount());
        entity.setStrength(signal.strength());
        entity.setDetectedAt(Instant.ofEpochMilli(signal.detectedAtTime()));
        entity.setStatus(signal.status());
        entity.setHistorical(signal.historical());
        entity.setNotified(false);
        signals.save(entity);
        log.info("SIGNAL {} {} {} @ {} after {} touches", signal.market(), signal.type(),
                signal.id(), signal.price(), signal.touchCount());
    }

    /** What {@link #reconcileWithEngine} changed. */
    public record Reconciliation(int levelsRetired, int signalsRetired) {
        public boolean changedAnything() {
            return levelsRetired > 0 || signalsRetired > 0;
        }
    }

    /**
     * Makes one market's stored statuses agree with what its engine currently tracks.
     *
     * <p>Rows are written only for levels the engine reports, so a row can be left saying ACTIVE
     * after the engine stopped following it: the level formed before the start of the replay
     * window and was therefore not rebuilt on the last restart, or it was dropped by the cap on
     * active levels. Nothing would ever update such a row again — it would show as an active line
     * forever, often almost on top of the level that replaced it. Every ACTIVE row the engine
     * does not hold as active is therefore moved to EXPIRED.
     *
     * <p>Then every signal still ACTIVE on a level that is not active any more takes its level's
     * fate: INVALIDATED for a broken level, SUPERSEDED for a superseded one, EXPIRED otherwise.
     *
     * @param trackedActiveIds ids of all levels the engine holds as ACTIVE right now
     */
    @Transactional
    public Reconciliation reconcileWithEngine(long marketId, Set<String> trackedActiveIds) {
        Set<UUID> tracked = new HashSet<>();
        for (String id : trackedActiveIds) {
            tracked.add(UUID.fromString(id));
        }
        List<UUID> untracked = new ArrayList<>();
        for (UUID id : levels.findIdsByMarketIdAndStatus(marketId, LevelStatus.ACTIVE)) {
            if (!tracked.contains(id)) {
                untracked.add(id);
            }
        }

        Instant now = Instant.now();
        int levelsRetired = 0;
        for (int from = 0; from < untracked.size(); from += RECONCILE_BATCH) {
            List<UUID> batch = untracked.subList(from, Math.min(untracked.size(), from + RECONCILE_BATCH));
            levelsRetired += levels.changeStatusIfStill(batch, LevelStatus.ACTIVE, LevelStatus.EXPIRED, now);
        }

        int signalsRetired = signals.retireActiveSignalsOfLevels(marketId, LevelStatus.BROKEN,
                SignalStatus.INVALIDATED, now)
                + signals.retireActiveSignalsOfLevels(marketId, LevelStatus.SUPERSEDED,
                SignalStatus.SUPERSEDED, now)
                + signals.retireActiveSignalsOfLevels(marketId, LevelStatus.EXPIRED,
                SignalStatus.EXPIRED, now);
        return new Reconciliation(levelsRetired, signalsRetired);
    }

    /** Marks a signal delivered. Called only after a notifier has actually accepted it. */
    @Transactional
    public void markNotified(UUID signalId) {
        signals.findById(signalId).ifPresent(entity -> {
            entity.setNotified(true);
            entity.setNotifiedAt(Instant.now());
            signals.save(entity);
        });
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value);
    }
}
