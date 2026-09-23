package com.cryptosignals.analysis.model;

import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.SignalStatus;

import java.util.List;

/**
 * A generated alert: a level reached its Nth valid touch without being broken.
 *
 * <p>This is a point-in-time snapshot. The level keeps evolving afterwards; the signal does not,
 * except for {@link #status} which the engine flips to INVALIDATED if the level later breaks.
 *
 * @param id              deterministic unique id, stable across re-runs
 * @param levelId         the level that produced it
 * @param market          symbol + timeframe
 * @param type            SUPPORT or RESISTANCE
 * @param price           level price at emission
 * @param zoneLower       zone lower bound at emission
 * @param zoneUpper       zone upper bound at emission
 * @param touchCount      touches at emission (>= minTouches)
 * @param touches         snapshot of every touch at emission
 * @param strength        level strength at emission
 * @param detectedAtIndex candle index that triggered emission
 * @param detectedAtTime  open time of that candle, epoch millis
 * @param status          ACTIVE / INVALIDATED / EXPIRED
 * @param historical      true when emitted while replaying backfilled history rather than live
 */
public record TradingSignal(
        String id,
        String levelId,
        MarketKey market,
        LevelType type,
        double price,
        double zoneLower,
        double zoneUpper,
        int touchCount,
        List<Touch> touches,
        double strength,
        int detectedAtIndex,
        long detectedAtTime,
        SignalStatus status,
        boolean historical) {

    public TradingSignal {
        touches = touches == null ? List.of() : List.copyOf(touches);
    }

    public TradingSignal withStatus(SignalStatus newStatus) {
        return new TradingSignal(id, levelId, market, type, price, zoneLower, zoneUpper, touchCount,
                touches, strength, detectedAtIndex, detectedAtTime, newStatus, historical);
    }

    public TradingSignal asHistorical(boolean value) {
        return new TradingSignal(id, levelId, market, type, price, zoneLower, zoneUpper, touchCount,
                touches, strength, detectedAtIndex, detectedAtTime, status, value);
    }
}
