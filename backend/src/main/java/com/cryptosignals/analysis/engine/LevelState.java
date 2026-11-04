package com.cryptosignals.analysis.engine;

import com.cryptosignals.analysis.model.BreakoutEvent;
import com.cryptosignals.analysis.model.DetectedLevel;
import com.cryptosignals.analysis.model.SwingPoint;
import com.cryptosignals.analysis.model.Touch;
import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.PriceAggregation;
import com.cryptosignals.domain.SupersedeReason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mutable working state of one level while the engine walks the series.
 *
 * <p>Deliberately not a record: this is the engine's scratch space. Callers outside the engine
 * consume {@link #snapshot()}, which is fully immutable.
 */
public final class LevelState {

    private final String id;
    private final MarketKey market;
    private final LevelType type;
    private final int createdAtIndex;
    private final long createdAtTime;

    /** Swing prices that formed this level, in discovery order. */
    private final List<Double> swingPrices = new ArrayList<>(4);
    private final List<Touch> touches = new ArrayList<>(4);

    private double price;
    private double zoneLower;
    private double zoneUpper;
    private double strength;
    private LevelStatus status = LevelStatus.ACTIVE;

    // ---- touch state machine -------------------------------------------------------------
    private TouchPhase phase = TouchPhase.ARMED;
    private int openStartIndex = -1;
    private int openEndIndex = -1;
    private int openExtremeIndex = -1;
    private long openStartTime = -1;
    private long openEndTime = -1;
    private long openExtremeTime = -1;
    private double openExtremePrice = Double.NaN;
    private double openLevelPrice = Double.NaN;
    /** 1-based ordinal of the currently open touch. */
    private int openTouchNumber;
    /** Consecutive candles spent fully outside the zone since leaving it. */
    private int candlesOutside;
    /** Furthest travel away from the level since leaving the zone, percent of level price. */
    private double maxDeparturePercent;

    private int previousTouchEndIndex = -1;
    private long previousTouchExtremeTime = -1;
    private double previousTouchExtremePrice = Double.NaN;

    // ---- breakout state ------------------------------------------------------------------
    private int breachRun;
    private int firstBreachIndex = -1;
    private BreakoutEvent breakout;

    // ---- supersede state -----------------------------------------------------------------
    private String supersededBy;
    private double supersededByPrice = Double.NaN;
    private long supersededAtTime = -1;
    private SupersedeReason supersedeReason;

    // ---- bookkeeping ---------------------------------------------------------------------
    private int lastSignalledTouchCount;
    private int processedCursor = Integer.MIN_VALUE;
    private int lastActivityIndex;
    private int lastTouchIndex = -1;
    private long lastTouchTime = -1;

    public LevelState(String id, MarketKey market, LevelType type, double anchorPrice,
                      int createdAtIndex, long createdAtTime) {
        this.id = id;
        this.market = market;
        this.type = type;
        this.createdAtIndex = createdAtIndex;
        this.createdAtTime = createdAtTime;
        this.price = anchorPrice;
        this.zoneLower = anchorPrice;
        this.zoneUpper = anchorPrice;
        this.lastActivityIndex = createdAtIndex;
        this.swingPrices.add(anchorPrice);
    }

    // ---- identity / geometry -------------------------------------------------------------

    public String id() {
        return id;
    }

    public MarketKey market() {
        return market;
    }

    public LevelType type() {
        return type;
    }

    public double price() {
        return price;
    }

    public double zoneLower() {
        return zoneLower;
    }

    public double zoneUpper() {
        return zoneUpper;
    }

    public LevelStatus status() {
        return status;
    }

    public int createdAtIndex() {
        return createdAtIndex;
    }

    public long createdAtTime() {
        return createdAtTime;
    }

    public int swingCount() {
        return swingPrices.size();
    }

    public double strength() {
        return strength;
    }

    public void strength(double value) {
        this.strength = value;
    }

    public boolean isActive() {
        return status == LevelStatus.ACTIVE;
    }

    /** Adds a clustered swing and re-derives the representative price. */
    public void addSwing(SwingPoint swing, PriceAggregation aggregation) {
        swingPrices.add(swing.price());
        this.price = aggregate(aggregation);
    }

    private double aggregate(PriceAggregation aggregation) {
        return switch (aggregation) {
            case MEAN -> {
                double sum = 0;
                for (double p : swingPrices) {
                    sum += p;
                }
                yield sum / swingPrices.size();
            }
            case MEDIAN -> {
                List<Double> sorted = new ArrayList<>(swingPrices);
                Collections.sort(sorted);
                int n = sorted.size();
                yield n % 2 == 1
                        ? sorted.get(n / 2)
                        : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
            }
            case EXTREME -> {
                double best = swingPrices.get(0);
                for (double p : swingPrices) {
                    best = type == LevelType.RESISTANCE ? Math.max(best, p) : Math.min(best, p);
                }
                yield best;
            }
        };
    }

    /** Re-derives the zone bounds from the current price and the supplied half-width. */
    public void applyZone(double halfWidth) {
        this.zoneLower = price - halfWidth;
        this.zoneUpper = price + halfWidth;
    }

    // ---- touch state machine accessors -----------------------------------------------------

    public TouchPhase phase() {
        return phase;
    }

    public void phase(TouchPhase value) {
        this.phase = value;
    }

    public int candlesOutside() {
        return candlesOutside;
    }

    public void candlesOutside(int value) {
        this.candlesOutside = value;
    }

    public double maxDeparturePercent() {
        return maxDeparturePercent;
    }

    public void maxDeparturePercent(double value) {
        this.maxDeparturePercent = value;
    }

    public boolean hasOpenTouch() {
        return openStartIndex >= 0;
    }

    public int touchCount() {
        return touches.size();
    }

    public List<Touch> touches() {
        return Collections.unmodifiableList(touches);
    }

    public int lastTouchIndex() {
        return lastTouchIndex;
    }

    public long lastTouchTime() {
        return lastTouchTime;
    }

    public int previousTouchEndIndex() {
        return previousTouchEndIndex;
    }

    /** Open time of the currently open touch's extreme candle, or -1 when no touch is open. */
    public long openExtremeTime() {
        return hasOpenTouch() ? openExtremeTime : -1L;
    }

    /**
     * Opens a new separate touch.
     *
     * @param extremePrice the furthest price this candle reached toward/through the zone
     */
    public void openTouch(int index, long time, double extremePrice) {
        this.openStartIndex = index;
        this.openEndIndex = index;
        this.openExtremeIndex = index;
        this.openStartTime = time;
        this.openEndTime = time;
        this.openExtremeTime = time;
        this.openExtremePrice = extremePrice;
        this.openLevelPrice = price;
        this.openTouchNumber = touches.size() + 1;
        this.candlesOutside = 0;
        this.maxDeparturePercent = 0;
        this.lastTouchIndex = index;
        this.lastTouchTime = time;
        this.lastActivityIndex = index;
        touches.add(buildOpenTouch());
    }

    /** Extends the currently open touch with another in-zone candle. */
    public void extendTouch(int index, long time, double candidateExtreme) {
        this.openEndIndex = index;
        this.openEndTime = time;
        boolean better = type == LevelType.RESISTANCE
                ? candidateExtreme > openExtremePrice
                : candidateExtreme < openExtremePrice;
        if (better) {
            this.openExtremePrice = candidateExtreme;
            this.openExtremeIndex = index;
            this.openExtremeTime = time;
        }
        this.candlesOutside = 0;
        this.lastTouchIndex = index;
        this.lastTouchTime = time;
        this.lastActivityIndex = index;
        touches.set(touches.size() - 1, buildOpenTouch());
    }

    /** Records how far price has travelled away from the level since leaving the zone. */
    public void recordDeparture(double departurePercent) {
        if (departurePercent > maxDeparturePercent) {
            this.maxDeparturePercent = departurePercent;
            if (!touches.isEmpty() && hasOpenTouch()) {
                touches.set(touches.size() - 1, buildOpenTouch());
            }
        }
    }

    /** Finalises the open touch: separation criteria are satisfied, the level is armed again. */
    public void closeTouch() {
        if (!hasOpenTouch()) {
            return;
        }
        touches.set(touches.size() - 1, buildClosedTouch());
        this.previousTouchEndIndex = openEndIndex;
        this.previousTouchExtremeTime = openExtremeTime;
        this.previousTouchExtremePrice = openExtremePrice;
        this.openStartIndex = -1;
        this.phase = TouchPhase.ARMED;
    }

    private Touch buildOpenTouch() {
        return buildTouch(false);
    }

    private Touch buildClosedTouch() {
        return buildTouch(true);
    }

    private Touch buildTouch(boolean closed) {
        double distance = openExtremePrice - openLevelPrice;
        double distancePercent = Math.abs(distance) / openLevelPrice * 100.0;
        int candlesSince = previousTouchEndIndex < 0 ? -1 : openStartIndex - previousTouchEndIndex - 1;
        long millisSince = previousTouchExtremeTime < 0 ? -1 : openStartTime - previousTouchExtremeTime;
        double priceDelta = Double.isNaN(previousTouchExtremePrice)
                ? 0.0 : openExtremePrice - previousTouchExtremePrice;
        return new Touch(openTouchNumber, openStartIndex, openEndIndex, openExtremeIndex, openStartTime,
                openEndTime, openExtremeTime, openExtremePrice, openLevelPrice, distance,
                distancePercent, candlesSince, millisSince, priceDelta, maxDeparturePercent, closed);
    }

    // ---- breakout state --------------------------------------------------------------------

    public int breachRun() {
        return breachRun;
    }

    public void registerBreach(int index) {
        if (breachRun == 0) {
            firstBreachIndex = index;
        }
        breachRun++;
    }

    public void resetBreachRun() {
        breachRun = 0;
        firstBreachIndex = -1;
    }

    public int firstBreachIndex() {
        return firstBreachIndex;
    }

    public void markBroken(BreakoutEvent event) {
        this.breakout = event;
        this.status = LevelStatus.BROKEN;
        if (hasOpenTouch()) {
            touches.set(touches.size() - 1, buildClosedTouch());
            this.openStartIndex = -1;
        }
    }

    public void markExpired() {
        this.status = LevelStatus.EXPIRED;
    }

    /**
     * Retires the level because another level of the same role has taken over. Any touch still in
     * progress is closed as it stands; no further touches are counted.
     */
    public void markSuperseded(LevelState by, long atTime, SupersedeReason reason) {
        this.status = LevelStatus.SUPERSEDED;
        this.supersededBy = by.id();
        this.supersededByPrice = by.price();
        this.supersededAtTime = atTime;
        this.supersedeReason = reason;
        if (hasOpenTouch()) {
            touches.set(touches.size() - 1, buildClosedTouch());
            this.openStartIndex = -1;
        }
        this.phase = TouchPhase.ARMED;
    }

    /**
     * Records a breakout of a level that was already superseded. The status stays SUPERSEDED — the
     * level had been retired before it broke — but the breakout is kept so its history is complete.
     */
    public void recordLateBreakout(BreakoutEvent event) {
        this.breakout = event;
    }

    public String supersededBy() {
        return supersededBy;
    }

    public BreakoutEvent breakout() {
        return breakout;
    }

    // ---- bookkeeping -----------------------------------------------------------------------

    public int lastSignalledTouchCount() {
        return lastSignalledTouchCount;
    }

    public void lastSignalledTouchCount(int value) {
        this.lastSignalledTouchCount = value;
    }

    public int processedCursor() {
        return processedCursor;
    }

    public void processedCursor(int value) {
        this.processedCursor = value;
    }

    public int lastActivityIndex() {
        return lastActivityIndex;
    }

    public DetectedLevel snapshot() {
        return new DetectedLevel(id, market, type, price, zoneLower, zoneUpper, strength, status,
                swingPrices.size(), createdAtIndex, createdAtTime, lastTouchIndex, lastTouchTime,
                List.copyOf(touches), breakout, supersededBy, supersededByPrice, supersededAtTime,
                supersedeReason);
    }
}
