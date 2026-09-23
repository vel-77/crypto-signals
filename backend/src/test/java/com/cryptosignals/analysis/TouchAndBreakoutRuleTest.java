package com.cryptosignals.analysis;

import com.cryptosignals.analysis.engine.LevelState;
import com.cryptosignals.analysis.engine.TouchEvent;
import com.cryptosignals.analysis.engine.TouchPhase;
import com.cryptosignals.analysis.model.BreakoutEvent;
import com.cryptosignals.analysis.strategy.ConfirmedBreakoutDetector;
import com.cryptosignals.analysis.strategy.SeparatedInteractionTouchDetector;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.domain.TouchMode;
import com.cryptosignals.fixtures.Candles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit-level tests of the two rules that encode the core business definitions. */
class TouchAndBreakoutRuleTest {

    private static final Timeframe TF = Timeframe.M5;
    private static final MarketKey MARKET = new MarketKey("BTCUSDT", TF);

    private final SeparatedInteractionTouchDetector touches = new SeparatedInteractionTouchDetector();
    private final ConfirmedBreakoutDetector breakouts = new ConfirmedBreakoutDetector();

    /** A resistance level at 100 with a 0.1% zone: 99.9 .. 100.1 */
    private LevelState resistanceAt100() {
        LevelState level = new LevelState("L1", MARKET, LevelType.RESISTANCE, 100.0, 0, Candles.T0);
        level.applyZone(0.1);
        return level;
    }

    private Candle bar(int index, double open, double high, double low, double close) {
        return Candles.candle(TF, Candles.T0 + (long) index * TF.durationMillis(),
                open, high, low, close);
    }

    @Nested
    @DisplayName("touch state machine")
    class Touches {

        private final AnalysisConfig config = AnalysisConfig.builder()
                .minCandlesBetweenTouches(3)
                .minDeparturePercent(0.5)
                .build();

        @Test
        @DisplayName("entering the zone opens a touch, staying in it extends the same touch")
        void entryOpensThenExtends() {
            LevelState level = resistanceAt100();

            assertThat(touches.onCandle(level, bar(1, 99.8, 100.0, 99.7, 99.95), 1, config))
                    .isEqualTo(TouchEvent.OPENED);
            assertThat(touches.onCandle(level, bar(2, 99.95, 100.05, 99.92, 100.0), 2, config))
                    .isEqualTo(TouchEvent.EXTENDED);
            assertThat(touches.onCandle(level, bar(3, 100.0, 100.02, 99.95, 99.98), 3, config))
                    .isEqualTo(TouchEvent.EXTENDED);

            assertThat(level.touchCount()).isEqualTo(1);
            assertThat(level.touches().get(0).candleCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("the recorded touch price is the furthest the candle reached")
        void touchRecordsTheExtreme() {
            LevelState level = resistanceAt100();
            touches.onCandle(level, bar(1, 99.8, 100.0, 99.7, 99.95), 1, config);
            touches.onCandle(level, bar(2, 99.95, 100.08, 99.9, 100.0), 2, config);

            assertThat(level.touches().get(0).extremePrice()).isEqualTo(100.08);
        }

        @Test
        @DisplayName("re-entering before separation continues the same touch")
        void reEntryDoesNotStartANewTouch() {
            LevelState level = resistanceAt100();
            touches.onCandle(level, bar(1, 99.8, 100.0, 99.7, 99.85), 1, config);
            // Leaves the zone for two candles only (below the 3 required).
            touches.onCandle(level, bar(2, 99.85, 99.88, 99.4, 99.5), 2, config);
            touches.onCandle(level, bar(3, 99.5, 99.6, 99.4, 99.5), 3, config);
            assertThat(level.phase()).isEqualTo(TouchPhase.DEPARTING);

            assertThat(touches.onCandle(level, bar(4, 99.5, 100.0, 99.5, 99.95), 4, config))
                    .isEqualTo(TouchEvent.EXTENDED);
            assertThat(level.touchCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("satisfying every separation criterion arms the level for a new touch")
        void separationClosesTheTouchAndArms() {
            LevelState level = resistanceAt100();
            touches.onCandle(level, bar(1, 99.8, 100.0, 99.7, 99.95), 1, config);
            for (int i = 2; i <= 4; i++) {
                // 0.6% away, beyond the 0.5% requirement, for three candles.
                touches.onCandle(level, bar(i, 99.5, 99.5, 99.4, 99.45), i, config);
            }

            assertThat(level.phase()).isEqualTo(TouchPhase.ARMED);
            assertThat(level.touches().get(0).closed()).isTrue();

            assertThat(touches.onCandle(level, bar(5, 99.5, 100.0, 99.5, 99.95), 5, config))
                    .isEqualTo(TouchEvent.OPENED);
            assertThat(level.touchCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("distance travelled away is enough on its own only if the candle count is too")
        void bothCriteriaAreRequired() {
            LevelState level = resistanceAt100();
            touches.onCandle(level, bar(1, 99.8, 100.0, 99.7, 99.95), 1, config);
            // Far enough away, but only for one candle.
            touches.onCandle(level, bar(2, 99.5, 99.5, 99.0, 99.1), 2, config);

            assertThat(level.phase()).isEqualTo(TouchPhase.DEPARTING);
            assertThat(level.touches().get(0).closed()).isFalse();
        }

        @Test
        @DisplayName("CLOSE mode ignores a wick that merely pokes into the zone")
        void touchModeChangesWhatCounts() {
            AnalysisConfig closeMode = config.toBuilder().touchMode(TouchMode.CLOSE).build();
            LevelState level = resistanceAt100();

            // Wick reaches 100.0 but the candle closes at 99.5, outside the zone.
            assertThat(touches.onCandle(level, bar(1, 99.4, 100.0, 99.3, 99.5), 1, closeMode))
                    .isEqualTo(TouchEvent.NONE);
            assertThat(level.touchCount()).isZero();
        }

        @Test
        @DisplayName("a support level measures departure upwards")
        void supportDepartureIsUpwards() {
            LevelState support = new LevelState("L2", MARKET, LevelType.SUPPORT, 100.0, 0, Candles.T0);
            support.applyZone(0.1);

            touches.onCandle(support, bar(1, 100.2, 100.3, 100.0, 100.05), 1, config);
            assertThat(support.touchCount()).isEqualTo(1);
            for (int i = 2; i <= 4; i++) {
                touches.onCandle(support, bar(i, 100.6, 100.7, 100.5, 100.6), i, config);
            }

            assertThat(support.phase()).isEqualTo(TouchPhase.ARMED);
            assertThat(support.touches().get(0).rejectionPercent()).isGreaterThanOrEqualTo(0.5);
        }
    }

    @Nested
    @DisplayName("breakout rule")
    class Breakouts {

        private final AnalysisConfig oneCandle = AnalysisConfig.builder()
                .breakoutTolerancePercent(0.2).confirmationCandles(1).build();
        private final AnalysisConfig twoCandles = AnalysisConfig.builder()
                .breakoutTolerancePercent(0.2).confirmationCandles(2).build();

        @Test
        @DisplayName("the threshold sits beyond the touch zone, on the correct side")
        void thresholdIsDirectional() {
            LevelState resistance = resistanceAt100();
            LevelState support = new LevelState("L3", MARKET, LevelType.SUPPORT, 100.0, 0, Candles.T0);

            assertThat(ConfirmedBreakoutDetector.threshold(resistance, oneCandle)).isEqualTo(100.2);
            assertThat(ConfirmedBreakoutDetector.threshold(support, oneCandle)).isEqualTo(99.8);
        }

        @Test
        @DisplayName("a close past the threshold confirms")
        void closeBeyondConfirms() {
            LevelState level = resistanceAt100();
            BreakoutEvent event = breakouts.detect(level, bar(1, 100.0, 100.5, 100.0, 100.3), 1, oneCandle);

            assertThat(event).isNotNull();
            assertThat(event.thresholdPrice()).isEqualTo(100.2);
            assertThat(event.confirmationPrice()).isEqualTo(100.3);
        }

        @Test
        @DisplayName("a wick past the threshold that closes back does not confirm")
        void wickAloneDoesNotConfirm() {
            LevelState level = resistanceAt100();
            assertThat(breakouts.detect(level, bar(1, 100.0, 101.0, 99.9, 100.0), 1, oneCandle)).isNull();
            assertThat(level.breachRun()).isZero();
        }

        @Test
        @DisplayName("a close just short of the threshold does not confirm")
        void mustClearTheToleranceNotJustTheLevel() {
            LevelState level = resistanceAt100();
            assertThat(breakouts.detect(level, bar(1, 100.0, 100.2, 100.0, 100.15), 1, oneCandle)).isNull();
        }

        @Test
        @DisplayName("the confirmation run resets when a candle falls back")
        void runMustBeConsecutive() {
            LevelState level = resistanceAt100();

            assertThat(breakouts.detect(level, bar(1, 100.0, 100.5, 100.0, 100.3), 1, twoCandles)).isNull();
            assertThat(level.breachRun()).isEqualTo(1);

            assertThat(breakouts.detect(level, bar(2, 100.3, 100.3, 99.9, 100.0), 2, twoCandles)).isNull();
            assertThat(level.breachRun()).isZero();

            assertThat(breakouts.detect(level, bar(3, 100.0, 100.5, 100.0, 100.3), 3, twoCandles)).isNull();
            assertThat(breakouts.detect(level, bar(4, 100.3, 100.6, 100.2, 100.4), 4, twoCandles)).isNotNull();
        }

        @Test
        @DisplayName("support breaks on a close below its threshold")
        void supportBreaksDownwards() {
            LevelState support = new LevelState("L4", MARKET, LevelType.SUPPORT, 100.0, 0, Candles.T0);
            support.applyZone(0.1);

            assertThat(breakouts.detect(support, bar(1, 100.0, 100.0, 99.5, 99.7), 1, oneCandle))
                    .isNotNull();
        }
    }
}
