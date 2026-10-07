package com.cryptosignals.analysis.model;

import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.MarketKey;

import java.util.List;
import java.util.Optional;

/**
 * Immutable snapshot of a discovered support/resistance level and everything that has happened
 * to it so far.
 *
 * @param id           deterministic identity, stable across re-runs of the same data and config
 * @param market       symbol + timeframe this level belongs to
 * @param type         SUPPORT or RESISTANCE
 * @param price        representative price of the zone
 * @param zoneLower    inclusive lower bound of the tolerance zone
 * @param zoneUpper    inclusive upper bound of the tolerance zone
 * @param strength     0..1 quality score
 * @param status       ACTIVE / BROKEN / EXPIRED
 * @param swingCount   number of swing points that formed this level
 * @param createdAtIndex candle index at which the level was created
 * @param createdAtTime  open time of that candle, epoch millis
 * @param lastTouchIndex index of the most recent touch, or -1
 * @param lastTouchTime  open time of the most recent touch, or -1
 * @param touches      all valid touches, in order
 * @param breakout     the confirmed breakout, if the level is BROKEN — or, for a SUPERSEDED level,
 *                     a breakout that happened after it was retired
 * @param supersededBy      id of the level that took over, if SUPERSEDED, else null
 * @param supersededByPrice price of that level, or NaN
 * @param supersededAtTime  open time of the candle on which it was superseded, or -1
 */
public record DetectedLevel(
        String id,
        MarketKey market,
        LevelType type,
        double price,
        double zoneLower,
        double zoneUpper,
        double strength,
        LevelStatus status,
        int swingCount,
        int createdAtIndex,
        long createdAtTime,
        int lastTouchIndex,
        long lastTouchTime,
        List<Touch> touches,
        BreakoutEvent breakout,
        String supersededBy,
        double supersededByPrice,
        long supersededAtTime) {

    public DetectedLevel {
        touches = touches == null ? List.of() : List.copyOf(touches);
    }

    public int touchCount() {
        return touches.size();
    }

    public Optional<BreakoutEvent> breakoutEvent() {
        return Optional.ofNullable(breakout);
    }

    /** Zone half-width as a percent of the level price. */
    public double zonePercent() {
        return (zoneUpper - zoneLower) / 2.0 / price * 100.0;
    }
}
