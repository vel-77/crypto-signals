package com.cryptosignals.analysis.engine;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.Ids;
import com.cryptosignals.analysis.model.AnalysisResult;
import com.cryptosignals.analysis.model.BreakoutEvent;
import com.cryptosignals.analysis.model.DetectedLevel;
import com.cryptosignals.analysis.model.SwingPoint;
import com.cryptosignals.analysis.model.TradingSignal;
import com.cryptosignals.analysis.strategy.BreakoutDetector;
import com.cryptosignals.analysis.strategy.DetectionContext;
import com.cryptosignals.analysis.strategy.LevelDetectionStrategy;
import com.cryptosignals.analysis.strategy.StrengthCalculator;
import com.cryptosignals.analysis.strategy.ToleranceCalculator;
import com.cryptosignals.analysis.strategy.TouchDetector;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.SignalStatus;
import com.cryptosignals.domain.SignalTrigger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * The detection engine for a single market (symbol + timeframe).
 *
 * <h2>Execution model</h2>
 * Candles are fed in strictly chronological order, one closed candle at a time, and the engine
 * never looks ahead. Historical replay and live streaming therefore run the <em>same code over the
 * same state machine</em>, which is what makes a backtest predictive of live behaviour and makes
 * repeated runs over the same data byte-for-byte identical (specification section 12).
 *
 * <h2>Per-candle order of operations</h2>
 * <ol>
 *   <li>Reject duplicates and out-of-order candles.</li>
 *   <li>Append to the bounded window and update ATR.</li>
 *   <li>For every active level: re-derive its zone, test for a breakout, then advance its touch
 *       state machine, then consider emitting a signal.</li>
 *   <li>Run the discovery strategy, which may create new levels from swings that this candle has
 *       just confirmed. New levels are replayed over the candles between the swing and now.</li>
 *   <li>Expire and prune levels.</li>
 * </ol>
 *
 * <p>Breakouts are evaluated <em>before</em> touches so that a candle which decisively clears the
 * level is recorded as the break rather than as another touch of it.
 *
 * <h2>Threading</h2>
 * Not thread-safe by design. One engine instance belongs to one market and is driven by one thread;
 * this is what guarantees a deterministic operation order and lets the hot path stay allocation-light.
 */
public final class LevelAnalysisEngine {

    private final MarketKey market;
    private final AnalysisConfig config;
    private final LevelDetectionStrategy detectionStrategy;
    private final TouchDetector touchDetector;
    private final BreakoutDetector breakoutDetector;
    private final ToleranceCalculator toleranceCalculator;
    private final StrengthCalculator strengthCalculator;
    private final boolean retainCompletedLevels;

    private final CandleWindow window;
    private final AtrCalculator atr;
    private final List<LevelState> levels = new ArrayList<>();
    private final List<TradingSignal> signals = new ArrayList<>();
    private final List<BreakoutEvent> breakouts = new ArrayList<>();

    /**
     * Touch events seen on the current candle that may raise a signal. Emission waits until the
     * whole candle has been processed: a candle that touches a level and, by its close, has already
     * moved past it towards a nearer level is a pass-through, not a test, and must not raise a
     * signal that is superseded the instant it appears.
     */
    private record PendingSignal(LevelState level, Candle candle, int index, TouchEvent event) {
    }

    private final List<PendingSignal> pendingSignals = new ArrayList<>();

    private long lastCandleOpenTime = Long.MIN_VALUE;
    private long firstCandleOpenTime = -1;
    private int candlesProcessed;
    private int duplicateCandles;
    private int outOfOrderCandles;
    private int detectedGaps;
    private boolean historical = true;

    public LevelAnalysisEngine(MarketKey market,
                               AnalysisConfig config,
                               LevelDetectionStrategy detectionStrategy,
                               TouchDetector touchDetector,
                               BreakoutDetector breakoutDetector,
                               ToleranceCalculator toleranceCalculator,
                               StrengthCalculator strengthCalculator,
                               boolean retainCompletedLevels) {
        this.market = market;
        this.config = config;
        this.detectionStrategy = detectionStrategy;
        this.touchDetector = touchDetector;
        this.breakoutDetector = breakoutDetector;
        this.toleranceCalculator = toleranceCalculator;
        this.strengthCalculator = strengthCalculator;
        this.retainCompletedLevels = retainCompletedLevels;
        this.window = new CandleWindow(windowCapacity(config));
        this.atr = new AtrCalculator(config.atrPeriod());
    }

    /**
     * The window only has to span the widest backward-looking rule, which is swing detection.
     * A generous margin is added so that changing a rule never silently truncates history.
     */
    private static int windowCapacity(AnalysisConfig config) {
        int swingSpan = config.swingLeftBars() + config.swingRightBars() + 1;
        return Math.max(swingSpan, config.swingRightBars() + 2) + 16;
    }

    /**
     * Marks subsequent signals as live rather than historical. Called once when backfill replay
     * finishes, so that startup does not spam notifications with year-old signals.
     */
    public void switchToLive() {
        this.historical = false;
    }

    public boolean isHistorical() {
        return historical;
    }

    /**
     * Feeds one <b>closed</b> candle.
     *
     * <p>Duplicates (same open time as the last accepted candle) are ignored, which makes replaying
     * an overlapping range harmless. Out-of-order candles are ignored rather than applied, because
     * applying them would corrupt every state machine that depends on chronological order.
     */
    public EngineUpdate onClosedCandle(Candle candle) {
        if (candle.openTime() == lastCandleOpenTime) {
            duplicateCandles++;
            return EngineUpdate.ignored(candle.openTime());
        }
        if (candle.openTime() < lastCandleOpenTime) {
            outOfOrderCandles++;
            return EngineUpdate.ignored(candle.openTime());
        }
        if (lastCandleOpenTime != Long.MIN_VALUE) {
            long expected = lastCandleOpenTime + market.timeframe().durationMillis();
            if (candle.openTime() > expected) {
                detectedGaps++;
            }
        }

        int index = window.append(candle);
        atr.update(candle);
        lastCandleOpenTime = candle.openTime();
        if (firstCandleOpenTime < 0) {
            firstCandleOpenTime = candle.openTime();
        }
        candlesProcessed++;

        List<TradingSignal> newSignals = new ArrayList<>(0);
        List<BreakoutEvent> newBreakouts = new ArrayList<>(0);
        List<TradingSignal> invalidated = new ArrayList<>(0);
        List<LevelState> changed = new ArrayList<>(0);

        // Indexed loop, not an enhanced for: a confirmed breakout can append a role-flipped level
        // while we are iterating. Appending to an ArrayList past the snapshot bound is safe and
        // leaves the freshly created level to be picked up from the next candle onwards.
        int levelCount = levels.size();
        for (int i = 0; i < levelCount; i++) {
            LevelState level = levels.get(i);
            if (level.isActive()) {
                advanceLevel(level, candle, index, newSignals, newBreakouts, invalidated, changed);
            } else if (level.status() == LevelStatus.SUPERSEDED) {
                trackLateBreakout(level, candle, index, newBreakouts, changed);
            }
        }

        detectionStrategy.onCandle(new Context(index, newSignals, newBreakouts, invalidated, changed));
        supersedeLevels(candle, index, invalidated, changed);
        for (PendingSignal pending : pendingSignals) {
            maybeEmitSignal(pending.level(), pending.candle(), pending.index(), pending.event(), newSignals);
        }
        pendingSignals.clear();

        expireLevels(index, changed);
        pruneLevels();

        List<DetectedLevel> changedSnapshots = new ArrayList<>(changed.size());
        for (LevelState level : changed) {
            changedSnapshots.add(level.snapshot());
        }
        return new EngineUpdate(true, index, candle.openTime(), newSignals, newBreakouts,
                invalidated, changedSnapshots);
    }

    /** Runs one candle through one level: zone, breakout, touch, signal. */
    private void advanceLevel(LevelState level, Candle candle, int index,
                              List<TradingSignal> newSignals, List<BreakoutEvent> newBreakouts,
                              List<TradingSignal> invalidated, List<LevelState> changed) {
        if (level.processedCursor() >= index) {
            return;
        }
        level.processedCursor(index);
        level.applyZone(toleranceCalculator.halfWidth(level.price(), atr.value(), config));

        BreakoutEvent breakout = breakoutDetector.detect(level, candle, index, config);
        if (breakout != null) {
            level.markBroken(breakout);
            breakouts.add(breakout);
            newBreakouts.add(breakout);
            changeSignalsOf(level.id(), SignalStatus.INVALIDATED, invalidated);
            trackChanged(changed, level);
            if (config.enableRoleFlip()) {
                createFlippedLevel(level, candle, index, changed);
            }
            return;
        }

        TouchEvent event = touchDetector.onCandle(level, candle, index, config);
        if (event != TouchEvent.NONE) {
            level.strength(strengthCalculator.strength(level, config));
            trackChanged(changed, level);
        }
        if (event == TouchEvent.OPENED || event == TouchEvent.CLOSED) {
            pendingSignals.add(new PendingSignal(level, candle, index, event));
        }
    }

    private void maybeEmitSignal(LevelState level, Candle candle, int index, TouchEvent event,
                                 List<TradingSignal> out) {
        if (!level.isActive() || level.touchCount() < config.minTouches()) {
            return;
        }
        if (level.strength() < config.minLevelStrength()) {
            return;
        }

        // Duplicate suppression (specification section 10): a level emits once, not on every
        // candle after its Nth touch. Enabling emitSignalOnEveryTouchAfterMin re-arms it for each
        // additional distinct touch instead.
        boolean alreadyEmitted = config.emitSignalOnEveryTouchAfterMin()
                ? level.lastSignalledTouchCount() >= level.touchCount()
                : level.lastSignalledTouchCount() > 0;
        if (alreadyEmitted) {
            return;
        }

        boolean triggered = config.signalTrigger() == SignalTrigger.ON_TOUCH
                ? event == TouchEvent.OPENED
                : event == TouchEvent.CLOSED;
        if (!triggered) {
            return;
        }

        TradingSignal signal = new TradingSignal(
                Ids.signalId(level.id(), level.touchCount()), level.id(), market, level.type(),
                level.price(), level.zoneLower(), level.zoneUpper(), level.touchCount(),
                level.touches(), level.strength(), index, candle.openTime(),
                SignalStatus.ACTIVE, historical);

        level.lastSignalledTouchCount(level.touchCount());
        signals.add(signal);
        out.add(signal);
    }

    /** Moves every still-active signal of a level to a new status, reporting each change. */
    private void changeSignalsOf(String levelId, SignalStatus newStatus, List<TradingSignal> out) {
        for (int i = 0; i < signals.size(); i++) {
            TradingSignal signal = signals.get(i);
            if (signal.levelId().equals(levelId) && signal.status() == SignalStatus.ACTIVE) {
                TradingSignal updated = signal.withStatus(newStatus);
                signals.set(i, updated);
                out.add(updated);
            }
        }
    }

    /**
     * Retires levels that a nearby, stronger level has taken over from (the "nearest level" rule).
     *
     * <p>For a resistance A, on the close of each candle:
     * <ol>
     *   <li>price has moved past it: {@code close > A.price};</li>
     *   <li>another active resistance B sits above it, within {@code supersedeMaxDistancePercent}
     *       of A's price;</li>
     *   <li>B has itself reached the touch threshold, so a weak, half-formed level can never retire
     *       an established one;</li>
     *   <li>B is now closer to price than A is.</li>
     * </ol>
     * Support is the mirror image. A is then SUPERSEDED, and any signal it raised changes status
     * with it.
     *
     * <p>Requiring price to have actually passed A matters: two nearby levels both remain active
     * while price is below them, because A is still the first obstacle. Only once price is through
     * A does B become the level that matters.
     *
     * <p>Every candidate is judged against the set of levels as it stood before this pass, so the
     * outcome does not depend on the order in which levels happen to be visited.
     */
    private void supersedeLevels(Candle candle, int index, List<TradingSignal> statusChanged,
                                 List<LevelState> changed) {
        if (!config.supersedeEnabled()) {
            return;
        }
        double close = candle.close();
        List<LevelState> active = new ArrayList<>();
        for (LevelState level : levels) {
            if (level.isActive()) {
                active.add(level);
            }
        }

        List<LevelState[]> retirements = new ArrayList<>();
        for (LevelState passed : active) {
            boolean resistance = passed.type() == LevelType.RESISTANCE;
            boolean pricePastIt = resistance ? close > passed.price() : close < passed.price();
            if (!pricePastIt) {
                continue;
            }
            double maxGap = passed.price() * config.supersedeMaxDistancePercent() / 100.0;
            double distanceToPassed = Math.abs(close - passed.price());

            LevelState successor = null;
            double successorDistance = Double.MAX_VALUE;
            for (LevelState candidate : active) {
                if (candidate == passed || candidate.type() != passed.type()
                        || candidate.touchCount() < config.minTouches()) {
                    continue;
                }
                double gap = resistance
                        ? candidate.price() - passed.price()
                        : passed.price() - candidate.price();
                if (gap <= 0 || gap > maxGap) {
                    continue;
                }
                double distance = Math.abs(candidate.price() - close);
                if (distance < distanceToPassed && distance < successorDistance) {
                    successor = candidate;
                    successorDistance = distance;
                }
            }
            if (successor != null) {
                retirements.add(new LevelState[]{passed, successor});
            }
        }

        for (LevelState[] pair : retirements) {
            LevelState passed = pair[0];
            passed.markSuperseded(pair[1], candle.openTime());
            changeSignalsOf(passed.id(), SignalStatus.SUPERSEDED, statusChanged);
            trackChanged(changed, passed);
        }
    }

    /**
     * Keeps watching a superseded level for a breakout so its history is complete. The level no
     * longer counts touches or signals; only the breakout is recorded, and its status is kept.
     */
    private void trackLateBreakout(LevelState level, Candle candle, int index,
                                   List<BreakoutEvent> newBreakouts, List<LevelState> changed) {
        if (level.breakout() != null || level.processedCursor() >= index) {
            return;
        }
        level.processedCursor(index);
        BreakoutEvent breakout = breakoutDetector.detect(level, candle, index, config);
        if (breakout != null) {
            level.recordLateBreakout(breakout);
            breakouts.add(breakout);
            newBreakouts.add(breakout);
            trackChanged(changed, level);
        }
    }


    /**
     * Optional "broken resistance becomes support" behaviour. Off by default: it is a trading
     * opinion rather than a mechanical consequence of the data, so it must be opted into.
     */
    private void createFlippedLevel(LevelState broken, Candle candle, int index,
                                    List<LevelState> changed) {
        LevelType flipped = broken.type().opposite();
        String id = Ids.levelId(market, flipped, candle.openTime(), broken.price());
        LevelState level = new LevelState(id, market, flipped, broken.price(), index,
                candle.openTime());
        level.applyZone(toleranceCalculator.halfWidth(level.price(), atr.value(), config));
        level.processedCursor(index);
        levels.add(level);
        trackChanged(changed, level);
    }

    private void expireLevels(int index, List<LevelState> changed) {
        if (config.levelExpiryCandles() <= 0) {
            return;
        }
        for (LevelState level : levels) {
            if (level.isActive() && index - level.lastActivityIndex() > config.levelExpiryCandles()) {
                level.markExpired();
                trackChanged(changed, level);
            }
        }
    }

    /**
     * Bounds memory in a long-running live process.
     *
     * <p>Completed levels are dropped once reported (they are already persisted), unless the engine
     * was built to retain them for a backtest report. Active levels are capped: when over the limit,
     * the least interesting ones go first — fewest touches, then lowest strength, then oldest — with
     * the level id as a final tie-break so pruning is deterministic. Levels that have already
     * emitted a signal are never pruned.
     */
    private void pruneLevels() {
        if (!retainCompletedLevels) {
            levels.removeIf(level -> !level.isActive() && level.lastSignalledTouchCount() == 0);
        }

        int active = 0;
        for (LevelState level : levels) {
            if (level.isActive()) {
                active++;
            }
        }
        int excess = active - config.maxActiveLevelsPerMarket();
        if (excess <= 0) {
            return;
        }

        List<LevelState> prunable = new ArrayList<>();
        for (LevelState level : levels) {
            if (level.isActive() && level.lastSignalledTouchCount() == 0) {
                prunable.add(level);
            }
        }
        prunable.sort(Comparator
                .comparingInt(LevelState::touchCount)
                .thenComparingDouble(LevelState::strength)
                .thenComparingInt(LevelState::createdAtIndex)
                .thenComparing(LevelState::id));

        Iterator<LevelState> it = prunable.iterator();
        while (excess > 0 && it.hasNext()) {
            levels.remove(it.next());
            excess--;
        }
    }

    private static void trackChanged(List<LevelState> changed, LevelState level) {
        if (!changed.contains(level)) {
            changed.add(level);
        }
    }

    // ---- reporting -------------------------------------------------------------------------

    public AnalysisResult result() {
        List<DetectedLevel> snapshots = new ArrayList<>(levels.size());
        for (LevelState level : levels) {
            snapshots.add(level.snapshot());
        }
        return new AnalysisResult(market, snapshots, List.copyOf(signals), List.copyOf(breakouts),
                candlesProcessed, firstCandleOpenTime, lastCandleOpenTime, config.fingerprint());
    }

    public List<DetectedLevel> activeLevels() {
        List<DetectedLevel> out = new ArrayList<>();
        for (LevelState level : levels) {
            if (level.status() == LevelStatus.ACTIVE) {
                out.add(level.snapshot());
            }
        }
        return out;
    }

    public MarketKey market() {
        return market;
    }

    public AnalysisConfig config() {
        return config;
    }

    public String strategyName() {
        return detectionStrategy.name();
    }

    public int candlesProcessed() {
        return candlesProcessed;
    }

    public int duplicateCandles() {
        return duplicateCandles;
    }

    public int outOfOrderCandles() {
        return outOfOrderCandles;
    }

    public int detectedGaps() {
        return detectedGaps;
    }

    public long lastCandleOpenTime() {
        return lastCandleOpenTime == Long.MIN_VALUE ? -1 : lastCandleOpenTime;
    }

    public double currentAtr() {
        return atr.value();
    }

    // ---- strategy seam ---------------------------------------------------------------------

    /** Binds the engine's internals to the discovery strategy for exactly one candle. */
    private final class Context implements DetectionContext {

        private final int index;
        private final List<TradingSignal> newSignals;
        private final List<BreakoutEvent> newBreakouts;
        private final List<TradingSignal> invalidated;
        private final List<LevelState> changed;

        private Context(int index, List<TradingSignal> newSignals, List<BreakoutEvent> newBreakouts,
                        List<TradingSignal> invalidated, List<LevelState> changed) {
            this.index = index;
            this.newSignals = newSignals;
            this.newBreakouts = newBreakouts;
            this.invalidated = invalidated;
            this.changed = changed;
        }

        @Override
        public AnalysisConfig config() {
            return config;
        }

        @Override
        public MarketKey market() {
            return market;
        }

        @Override
        public CandleWindow window() {
            return window;
        }

        @Override
        public int index() {
            return index;
        }

        @Override
        public double atr() {
            return atr.value();
        }

        @Override
        public List<LevelState> activeLevels(LevelType type) {
            List<LevelState> out = new ArrayList<>();
            for (LevelState level : levels) {
                if (level.isActive() && level.type() == type) {
                    out.add(level);
                }
            }
            return out;
        }

        /**
         * Creates a level anchored at a swing and brings it up to date.
         *
         * <p>The swing printed {@code swingRightBars} candles ago, so the level is created with
         * that candle's index and then replayed over every candle from the swing up to the current
         * one. The replay is what makes the swing itself count as the level's first touch, and it
         * is bounded by {@code swingRightBars}, so it costs a handful of iterations.
         */
        @Override
        public LevelState createLevel(SwingPoint origin) {
            String id = Ids.levelId(market, origin.type(), origin.time(), origin.price());
            LevelState level = new LevelState(id, market, origin.type(), origin.price(),
                    origin.index(), origin.time());
            level.applyZone(toleranceCalculator.halfWidth(level.price(), atr.value(), config));
            level.strength(strengthCalculator.strength(level, config));
            levels.add(level);

            for (int i = origin.index(); i <= index; i++) {
                if (!window.has(i) || !level.isActive()) {
                    continue;
                }
                advanceLevel(level, window.at(i), i, newSignals, newBreakouts, invalidated, changed);
            }
            trackChanged(changed, level);
            return level;
        }

        @Override
        public void attachSwing(LevelState level, SwingPoint swing) {
            level.addSwing(swing, config.levelPriceAggregation());
            level.applyZone(toleranceCalculator.halfWidth(level.price(), atr.value(), config));
            level.strength(strengthCalculator.strength(level, config));
            trackChanged(changed, level);
        }
    }
}
