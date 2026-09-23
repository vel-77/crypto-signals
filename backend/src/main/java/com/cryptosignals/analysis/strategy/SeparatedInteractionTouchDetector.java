package com.cryptosignals.analysis.strategy;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.engine.LevelState;
import com.cryptosignals.analysis.engine.TouchEvent;
import com.cryptosignals.analysis.engine.TouchPhase;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.TouchMode;

/**
 * The default answer to "what counts as a separate touch" (specification section 3).
 *
 * <p>A touch is not a candle, it is an <b>interaction</b>: a run of candles that begins when price
 * enters the zone and ends only once price has convincingly left. Four candles resting on a level
 * are therefore one touch, not four.
 *
 * <p>The state machine:
 * <pre>
 *   ARMED      --price enters zone-->  IN_TOUCH        (a new touch is recorded)
 *   IN_TOUCH   --price stays in zone-> IN_TOUCH        (same touch, extreme updated)
 *   IN_TOUCH   --price leaves zone---> DEPARTING       (same touch, still open)
 *   DEPARTING  --price re-enters-----> IN_TOUCH        (same touch resumes: NOT a new touch)
 *   DEPARTING  --separation met------> ARMED           (touch closed; next entry is a new touch)
 * </pre>
 *
 * <p>Separation requires <b>all</b> configured criteria simultaneously:
 * <ul>
 *   <li>{@code minCandlesBetweenTouches} candles spent fully outside the zone,</li>
 *   <li>price travelling at least {@code minDeparturePercent} away from the level (downwards for
 *       resistance, upwards for support) — expressed as a percentage, so it is scale-free,</li>
 *   <li>optionally at least {@code minTimeBetweenTouches} of wall-clock time, which matters when
 *       the series contains gaps from exchange downtime.</li>
 * </ul>
 * Requiring all of them together is the conservative reading: a shallow drift out of the zone for a
 * few candles is one continuing test, not two.
 */
public final class SeparatedInteractionTouchDetector implements TouchDetector {

    @Override
    public String name() {
        return "separated-interaction";
    }

    @Override
    public TouchEvent onCandle(LevelState level, Candle candle, int index, AnalysisConfig config) {
        boolean inZone = inZone(level, candle, config.touchMode());

        return switch (level.phase()) {
            case ARMED -> {
                if (!inZone) {
                    yield TouchEvent.NONE;
                }
                level.openTouch(index, candle.openTime(), touchPrice(level, candle, config.touchMode()));
                level.phase(TouchPhase.IN_TOUCH);
                yield TouchEvent.OPENED;
            }
            case IN_TOUCH -> {
                if (inZone) {
                    level.extendTouch(index, candle.openTime(), touchPrice(level, candle, config.touchMode()));
                    yield TouchEvent.EXTENDED;
                }
                level.phase(TouchPhase.DEPARTING);
                level.candlesOutside(1);
                level.recordDeparture(departurePercent(level, candle));
                yield closeIfSeparated(level, candle, config) ? TouchEvent.CLOSED : TouchEvent.NONE;
            }
            case DEPARTING -> {
                if (inZone) {
                    // Price came back before the separation criteria were met: this is still the
                    // same interaction with the level, so no new touch is recorded.
                    level.phase(TouchPhase.IN_TOUCH);
                    level.extendTouch(index, candle.openTime(), touchPrice(level, candle, config.touchMode()));
                    yield TouchEvent.EXTENDED;
                }
                level.candlesOutside(level.candlesOutside() + 1);
                level.recordDeparture(departurePercent(level, candle));
                yield closeIfSeparated(level, candle, config) ? TouchEvent.CLOSED : TouchEvent.NONE;
            }
        };
    }

    /** True when the candle reaches into the zone, according to the configured mode. */
    private boolean inZone(LevelState level, Candle candle, TouchMode mode) {
        return switch (mode) {
            case WICK -> candle.low() <= level.zoneUpper() && candle.high() >= level.zoneLower();
            case BODY -> candle.bodyLow() <= level.zoneUpper() && candle.bodyHigh() >= level.zoneLower();
            case CLOSE -> candle.close() >= level.zoneLower() && candle.close() <= level.zoneUpper();
        };
    }

    /**
     * The price recorded for the touch: the furthest the candle pushed into the level, measured
     * consistently with the touch mode.
     */
    private double touchPrice(LevelState level, Candle candle, TouchMode mode) {
        boolean resistance = level.type() == LevelType.RESISTANCE;
        return switch (mode) {
            case WICK -> resistance ? candle.high() : candle.low();
            case BODY -> resistance ? candle.bodyHigh() : candle.bodyLow();
            case CLOSE -> candle.close();
        };
    }

    /** How far this candle travelled away from the level, as a percent of the level price. */
    private double departurePercent(LevelState level, Candle candle) {
        double away = level.type() == LevelType.RESISTANCE
                ? level.price() - candle.low()
                : candle.high() - level.price();
        return Math.max(0.0, away / level.price() * 100.0);
    }

    private boolean closeIfSeparated(LevelState level, Candle candle, AnalysisConfig config) {
        if (level.candlesOutside() < config.minCandlesBetweenTouches()) {
            return false;
        }
        if (level.maxDeparturePercent() < config.minDeparturePercent()) {
            return false;
        }
        long minMillis = config.minTimeBetweenTouches().toMillis();
        if (minMillis > 0 && level.hasOpenTouch()
                && candle.openTime() - level.openExtremeTime() < minMillis) {
            return false;
        }
        level.closeTouch();
        return true;
    }
}
