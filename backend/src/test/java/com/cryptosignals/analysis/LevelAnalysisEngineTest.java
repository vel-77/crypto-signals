package com.cryptosignals.analysis;

import com.cryptosignals.analysis.engine.LevelAnalysisEngine;
import com.cryptosignals.analysis.model.AnalysisResult;
import com.cryptosignals.analysis.model.DetectedLevel;
import com.cryptosignals.analysis.model.Touch;
import com.cryptosignals.analysis.model.TradingSignal;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.SignalStatus;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.fixtures.Candles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end behaviour of the detection engine against synthetic, fully deterministic series.
 *
 * <p>Every fixture is a hand-checkable price path, so a failure points at a rule rather than at
 * market noise. The scenarios mirror the business rules one to one.
 */
class LevelAnalysisEngineTest {

    private static final Timeframe TF = Timeframe.M5;
    private final LevelAnalysisEngineFactory factory = new LevelAnalysisEngineFactory();

    private AnalysisResult analyze(List<Candle> candles) {
        return analyze(candles, AnalysisConfig.defaults(), TF);
    }

    private AnalysisResult analyze(List<Candle> candles, AnalysisConfig config, Timeframe timeframe) {
        return factory.analyze(new MarketKey("BTCUSDT", timeframe), config, candles);
    }

    /** Three peaks at 100 000 separated by dips to 99 000, then a drift away. */
    private static List<Candle> threeTouchResistance() {
        return Candles.fromPath(TF, Candles.zigzag(99_000, 15,
                100_000, 99_000, 100_000, 99_000, 100_000, 99_500));
    }

    /** Three troughs at 99 000 separated by rallies to 100 000. */
    private static List<Candle> threeTouchSupport() {
        return Candles.fromPath(TF, Candles.zigzag(100_000, 15,
                99_000, 100_000, 99_000, 100_000, 99_000, 99_500));
    }

    private static DetectedLevel levelOfType(AnalysisResult result, LevelType type) {
        return result.levels().stream()
                .filter(l -> l.type() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " level was discovered"));
    }

    // ---------------------------------------------------------------- discovery + signalling

    @Nested
    @DisplayName("automatic discovery and third-touch signalling")
    class Discovery {

        @Test
        @DisplayName("finds the resistance without being told the price, and signals on touch 3")
        void discoversResistanceAndSignalsOnThirdTouch() {
            AnalysisResult result = analyze(threeTouchResistance());

            DetectedLevel level = levelOfType(result, LevelType.RESISTANCE);
            assertThat(level.price()).isEqualTo(100_000.0);
            assertThat(level.zoneLower()).isEqualTo(99_900.0);
            assertThat(level.zoneUpper()).isEqualTo(100_100.0);
            assertThat(level.status()).isEqualTo(LevelStatus.ACTIVE);
            assertThat(level.touchCount()).isEqualTo(3);
            assertThat(level.breakoutEvent()).isEmpty();

            assertThat(result.signals()).hasSize(1);
            TradingSignal signal = result.signals().get(0);
            assertThat(signal.type()).isEqualTo(LevelType.RESISTANCE);
            assertThat(signal.touchCount()).isEqualTo(3);
            assertThat(signal.levelId()).isEqualTo(level.id());
            assertThat(signal.status()).isEqualTo(SignalStatus.ACTIVE);
            assertThat(signal.touches()).hasSize(3);
        }

        @Test
        @DisplayName("finds the support without being told the price, and signals on touch 3")
        void discoversSupportAndSignalsOnThirdTouch() {
            AnalysisResult result = analyze(threeTouchSupport());

            DetectedLevel level = levelOfType(result, LevelType.SUPPORT);
            assertThat(level.price()).isEqualTo(99_000.0);
            assertThat(level.touchCount()).isEqualTo(3);

            assertThat(result.signals()).hasSize(1);
            assertThat(result.signals().get(0).type()).isEqualTo(LevelType.SUPPORT);
        }

        @Test
        @DisplayName("two touches are not enough")
        void doesNotSignalBeforeTheThirdTouch() {
            AnalysisResult result = analyze(Candles.fromPath(TF, Candles.zigzag(99_000, 15,
                    100_000, 99_000, 100_000, 99_500)));

            assertThat(levelOfType(result, LevelType.RESISTANCE).touchCount()).isEqualTo(2);
            assertThat(result.signals()).isEmpty();
        }

        @Test
        @DisplayName("each touch carries its own timestamp, price and spacing")
        void signalCarriesFullTouchDetail() {
            AnalysisResult result = analyze(threeTouchResistance());
            List<Touch> touches = result.signals().get(0).touches();

            assertThat(touches).extracting(Touch::touchNumber).containsExactly(1, 2, 3);
            assertThat(touches).allSatisfy(t -> {
                assertThat(t.extremePrice()).isCloseTo(100_000.0, org.assertj.core.data.Offset.offset(150.0));
                assertThat(t.startTime()).isGreaterThan(0);
                assertThat(t.levelPriceAtTouch()).isEqualTo(100_000.0);
            });
            // First touch has no predecessor; later ones record their separation.
            assertThat(touches.get(0).candlesSincePreviousTouch()).isEqualTo(-1);
            assertThat(touches.get(1).candlesSincePreviousTouch()).isGreaterThanOrEqualTo(
                    AnalysisConfig.defaults().minCandlesBetweenTouches());
            assertThat(touches.get(1).millisSincePreviousTouch())
                    .isEqualTo((touches.get(1).startTime() - touches.get(0).extremeTime()));
        }

        @Test
        @DisplayName("support and resistance on the same series are independent levels")
        void supportAndResistanceAreTrackedSeparately() {
            AnalysisResult result = analyze(Candles.fromPath(TF, Candles.zigzag(99_000, 15,
                    100_000, 99_000, 100_000, 99_000, 100_000, 99_000, 100_000, 99_500)));

            assertThat(levelOfType(result, LevelType.RESISTANCE).type()).isEqualTo(LevelType.RESISTANCE);
            assertThat(levelOfType(result, LevelType.SUPPORT).type()).isEqualTo(LevelType.SUPPORT);
            assertThat(result.signals()).extracting(TradingSignal::type)
                    .containsExactlyInAnyOrder(LevelType.RESISTANCE, LevelType.SUPPORT);
        }
    }

    // ---------------------------------------------------------------- separating touches

    @Nested
    @DisplayName("separating consecutive candles into distinct touches")
    class TouchSeparation {

        @Test
        @DisplayName("a run of candles resting on the level is one touch, not many")
        void consecutiveCandlesCountAsASingleTouch() {
            List<Candle> series = new ArrayList<>(
                    Candles.fromPath(TF, Candles.zigzag(99_000, 15, 100_000)));
            Candles.appendFlat(TF, series, 100_000, 8);

            AnalysisResult result = analyze(series);
            DetectedLevel level = levelOfType(result, LevelType.RESISTANCE);

            assertThat(level.touchCount()).isEqualTo(1);
            Touch only = level.touches().get(0);
            assertThat(only.candleCount()).isGreaterThan(8);
            assertThat(result.signals()).isEmpty();
        }

        @Test
        @DisplayName("leaving the zone too briefly does not start a new touch")
        void tooFewCandlesOutsideDoesNotSeparate() {
            // A full round trip down to 99 000 and back, but only ~7 candles outside the zone,
            // below the default minimum of 10.
            AnalysisResult result = analyze(Candles.fromPath(TF,
                    Candles.zigzag(99_000, 4, 100_000, 99_000, 100_000)));

            assertThat(levelOfType(result, LevelType.RESISTANCE).touchCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("drifting only slightly away does not start a new touch")
        void tooShallowADepartureDoesNotSeparate() {
            // Price leaves the zone for a long time but never travels the required 0.3% away.
            AnalysisResult result = analyze(Candles.fromPath(TF, Candles.zigzag(99_000, 8,
                    100_000, 99_850, 99_850, 99_850, 100_000)));

            assertThat(levelOfType(result, LevelType.RESISTANCE).touchCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("relaxing the separation rules admits more touches from the same data")
        void separationRulesAreConfigurable() {
            List<Candle> series = Candles.fromPath(TF,
                    Candles.zigzag(99_000, 4, 100_000, 99_000, 100_000, 99_000, 100_000));

            AnalysisResult strict = analyze(series, AnalysisConfig.defaults(), TF);
            AnalysisResult relaxed = analyze(series, AnalysisConfig.builder()
                    .minCandlesBetweenTouches(2)
                    .minDeparturePercent(0.2)
                    .build(), TF);

            assertThat(levelOfType(strict, LevelType.RESISTANCE).touchCount())
                    .isLessThan(levelOfType(relaxed, LevelType.RESISTANCE).touchCount());
        }
    }

    // ---------------------------------------------------------------- breakouts

    @Nested
    @DisplayName("breakout rules")
    class Breakouts {

        @Test
        @DisplayName("a confirmed close beyond the threshold breaks the level and invalidates its signal")
        void confirmedCloseBreaksLevel() {
            AnalysisResult result = analyze(Candles.fromPath(TF, Candles.zigzag(99_000, 15,
                    100_000, 99_000, 100_000, 99_000, 100_000, 101_000)));

            DetectedLevel level = levelOfType(result, LevelType.RESISTANCE);
            assertThat(level.status()).isEqualTo(LevelStatus.BROKEN);
            assertThat(level.breakoutEvent()).isPresent();
            assertThat(level.breakoutEvent().get().thresholdPrice()).isEqualTo(100_200.0);
            assertThat(level.breakoutEvent().get().confirmationPrice()).isGreaterThan(100_200.0);
            assertThat(level.breakoutEvent().get().touchCountAtBreak()).isEqualTo(3);

            assertThat(result.signals()).hasSize(1);
            assertThat(result.signals().get(0).status()).isEqualTo(SignalStatus.INVALIDATED);
        }

        @Test
        @DisplayName("a lone wick far beyond the level is not a breakout")
        void singleWickIsNotABreakout() {
            List<Candle> series = new ArrayList<>(Candles.fromPath(TF, Candles.zigzag(99_000, 15,
                    100_000, 99_000, 100_000, 99_000, 100_000)));
            // Wick to 100 900 — well past the 100 200 threshold — but the candle closes back inside.
            series.add(Candles.next(TF, series, 100_000, 100_900, 99_950, 100_000));
            series.add(Candles.next(TF, series, 100_000, 100_000, 99_900, 99_950));

            AnalysisResult result = analyze(series);

            assertThat(levelOfType(result, LevelType.RESISTANCE).status()).isEqualTo(LevelStatus.ACTIVE);
            assertThat(result.breakouts()).isEmpty();
            assertThat(result.signals().get(0).status()).isEqualTo(SignalStatus.ACTIVE);
        }

        @Test
        @DisplayName("wickBreakAllowed turns that same wick into a breakout")
        void wickBreakIsOptIn() {
            List<Candle> series = new ArrayList<>(Candles.fromPath(TF, Candles.zigzag(99_000, 15,
                    100_000, 99_000, 100_000, 99_000, 100_000)));
            series.add(Candles.next(TF, series, 100_000, 100_900, 99_950, 100_000));

            AnalysisResult result = analyze(series,
                    AnalysisConfig.builder().wickBreakAllowed(true).build(), TF);

            assertThat(levelOfType(result, LevelType.RESISTANCE).status()).isEqualTo(LevelStatus.BROKEN);
        }

        @Test
        @DisplayName("confirmationCandles genuinely means consecutive")
        void confirmationCandlesAreRequiredToBeConsecutive() {
            List<Candle> series = new ArrayList<>(Candles.fromPath(TF, Candles.zigzag(99_000, 15,
                    100_000, 99_000, 100_000, 99_000, 100_000)));
            // One close beyond the threshold, then back inside: not enough for 2 confirmations.
            series.add(Candles.next(TF, series, 100_000, 100_400, 100_000, 100_300));
            series.add(Candles.next(TF, series, 100_300, 100_300, 100_000, 100_050));

            AnalysisResult twoNeeded = analyze(series,
                    AnalysisConfig.builder().confirmationCandles(2).build(), TF);
            AnalysisResult oneNeeded = analyze(series,
                    AnalysisConfig.builder().confirmationCandles(1).build(), TF);

            assertThat(levelOfType(twoNeeded, LevelType.RESISTANCE).status()).isEqualTo(LevelStatus.ACTIVE);
            assertThat(levelOfType(oneNeeded, LevelType.RESISTANCE).status()).isEqualTo(LevelStatus.BROKEN);
        }

        @Test
        @DisplayName("a support level breaks downwards")
        void supportBreaksDownwards() {
            AnalysisResult result = analyze(Candles.fromPath(TF, Candles.zigzag(100_000, 15,
                    99_000, 100_000, 99_000, 100_000, 99_000, 98_000)));

            DetectedLevel level = levelOfType(result, LevelType.SUPPORT);
            assertThat(level.status()).isEqualTo(LevelStatus.BROKEN);
            assertThat(level.breakoutEvent().get().thresholdPrice()).isEqualTo(99_000.0 * (1 - 0.002));
        }
    }

    // ---------------------------------------------------------------- duplicate suppression

    @Nested
    @DisplayName("duplicate signal prevention")
    class DuplicateSuppression {

        @Test
        @DisplayName("touches 4 and 5 do not re-emit")
        void oneSignalPerLevel() {
            AnalysisResult result = analyze(Candles.fromPath(TF, Candles.zigzag(99_000, 15,
                    100_000, 99_000, 100_000, 99_000, 100_000, 99_000, 100_000, 99_000,
                    100_000, 99_500)));

            DetectedLevel level = levelOfType(result, LevelType.RESISTANCE);
            assertThat(level.touchCount()).isEqualTo(5);
            assertThat(result.signals()).filteredOn(s -> s.type() == LevelType.RESISTANCE).hasSize(1);
            assertThat(result.signals().get(0).touchCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("resting on the level after the signal does not re-emit every candle")
        void noSignalPerCandleAfterTheThirdTouch() {
            List<Candle> series = new ArrayList<>(threeTouchResistance());
            Candles.appendFlat(TF, series, 100_000, 30);

            AnalysisResult result = analyze(series);

            assertThat(result.signals()).filteredOn(s -> s.type() == LevelType.RESISTANCE).hasSize(1);
        }

        @Test
        @DisplayName("opting in re-emits on each further distinct touch")
        void repeatedEmissionIsOptIn() {
            AnalysisResult result = analyze(Candles.fromPath(TF, Candles.zigzag(99_000, 15,
                            100_000, 99_000, 100_000, 99_000, 100_000, 99_000, 100_000, 99_500)),
                    AnalysisConfig.builder().emitSignalOnEveryTouchAfterMin(true).build(), TF);

            assertThat(result.signals()).filteredOn(s -> s.type() == LevelType.RESISTANCE)
                    .extracting(TradingSignal::touchCount).containsExactly(3, 4);
        }
    }

    // ---------------------------------------------------------------- determinism and scale

    @Nested
    @DisplayName("determinism, price scale and timeframe independence")
    class Invariance {

        @Test
        @DisplayName("running the same data twice produces identical results, ids included")
        void isDeterministic() {
            List<Candle> series = threeTouchResistance();
            assertThat(analyze(series)).isEqualTo(analyze(series));
        }

        @Test
        @DisplayName("feeding candles one at a time matches the batch run")
        void incrementalMatchesBatch() {
            List<Candle> series = threeTouchResistance();
            MarketKey market = new MarketKey("BTCUSDT", TF);

            LevelAnalysisEngine engine = factory.create(market, AnalysisConfig.defaults(), true);
            for (Candle candle : series) {
                engine.onClosedCandle(candle);
            }

            assertThat(engine.result()).isEqualTo(analyze(series));
        }

        /**
         * The same pattern at wildly different absolute prices must produce the same structure.
         * Only the prices scale; touch indices, touch counts and the signalling candle do not move.
         */
        @ParameterizedTest(name = "scale factor {0}")
        @ValueSource(doubles = {1e-9, 1e-5, 1e-4, 1e-3, 1.0, 10.0})
        @DisplayName("behaviour is identical at any price scale")
        void isPriceScaleIndependent(double factor) {
            double[] basePath = Candles.zigzag(99_000, 15,
                    100_000, 99_000, 100_000, 99_000, 100_000, 99_500);

            AnalysisResult base = analyze(Candles.fromPath(TF, basePath));
            AnalysisResult scaled = analyze(Candles.fromPath(TF, Candles.scale(basePath, factor)));

            DetectedLevel baseLevel = levelOfType(base, LevelType.RESISTANCE);
            DetectedLevel scaledLevel = levelOfType(scaled, LevelType.RESISTANCE);

            assertThat(scaledLevel.touchCount()).isEqualTo(baseLevel.touchCount());
            assertThat(scaledLevel.touches()).extracting(Touch::startIndex)
                    .isEqualTo(baseLevel.touches().stream().map(Touch::startIndex).toList());
            assertThat(scaled.signals()).hasSameSizeAs(base.signals());
            assertThat(scaled.signals().get(0).detectedAtIndex())
                    .isEqualTo(base.signals().get(0).detectedAtIndex());
            // The level itself simply scales.
            assertThat(scaledLevel.price()).isCloseTo(baseLevel.price() * factor,
                    org.assertj.core.data.Percentage.withPercentage(1e-6));
        }

        @ParameterizedTest(name = "timeframe {0}")
        @EnumSource(value = Timeframe.class, names = {"M1", "M5", "M15", "H1", "H4", "D1"})
        @DisplayName("behaviour is identical on every timeframe")
        void isTimeframeIndependent(Timeframe timeframe) {
            double[] path = Candles.zigzag(99_000, 15,
                    100_000, 99_000, 100_000, 99_000, 100_000, 99_500);

            AnalysisResult result = analyze(Candles.fromPath(timeframe, path),
                    AnalysisConfig.defaults(), timeframe);

            assertThat(levelOfType(result, LevelType.RESISTANCE).touchCount()).isEqualTo(3);
            assertThat(result.signals()).hasSize(1);
            assertThat(result.signals().get(0).detectedAtIndex()).isEqualTo(74);
            assertThat(result.signals().get(0).market().timeframe()).isEqualTo(timeframe);
        }

        @Test
        @DisplayName("a signal id is stable across runs and unique per level and touch")
        void signalIdsAreDeterministicAndUnique() {
            AnalysisResult first = analyze(threeTouchResistance());
            AnalysisResult second = analyze(threeTouchResistance());

            assertThat(first.signals().get(0).id()).isEqualTo(second.signals().get(0).id());
            assertThat(first.levels()).extracting(DetectedLevel::id).doesNotHaveDuplicates();
        }
    }

    // ---------------------------------------------------------------- resilience

    @Nested
    @DisplayName("malformed input handling")
    class Resilience {

        @Test
        @DisplayName("a repeated candle is ignored rather than double counted")
        void ignoresDuplicateCandles() {
            List<Candle> series = threeTouchResistance();
            List<Candle> withDuplicates = new ArrayList<>();
            for (Candle candle : series) {
                withDuplicates.add(candle);
                withDuplicates.add(candle);
            }

            AnalysisResult clean = analyze(series);
            AnalysisResult dirty = analyze(withDuplicates);

            assertThat(dirty.candlesProcessed()).isEqualTo(clean.candlesProcessed());
            assertThat(levelOfType(dirty, LevelType.RESISTANCE).touchCount())
                    .isEqualTo(levelOfType(clean, LevelType.RESISTANCE).touchCount());
            assertThat(dirty.signals()).hasSameSizeAs(clean.signals());
        }

        @Test
        @DisplayName("an out-of-order candle is rejected, leaving state untouched")
        void ignoresOutOfOrderCandles() {
            List<Candle> series = threeTouchResistance();
            MarketKey market = new MarketKey("BTCUSDT", TF);
            LevelAnalysisEngine engine = factory.create(market, AnalysisConfig.defaults(), true);

            for (Candle candle : series) {
                engine.onClosedCandle(candle);
            }
            var update = engine.onClosedCandle(series.get(10));

            assertThat(update.accepted()).isFalse();
            assertThat(engine.outOfOrderCandles()).isEqualTo(1);
            assertThat(engine.result()).isEqualTo(analyze(series));
        }

        @Test
        @DisplayName("a gap in the series is counted but does not derail the analysis")
        void toleratesMissingCandles() {
            List<Candle> series = new ArrayList<>(threeTouchResistance());
            series.remove(50);
            series.remove(50);

            MarketKey market = new MarketKey("BTCUSDT", TF);
            LevelAnalysisEngine engine = factory.create(market, AnalysisConfig.defaults(), true);
            series.forEach(engine::onClosedCandle);

            assertThat(engine.detectedGaps()).isEqualTo(1);
            assertThat(engine.result().levels()).isNotEmpty();
        }

        @Test
        @DisplayName("an empty series is analysable and yields nothing")
        void handlesEmptySeries() {
            AnalysisResult result = analyze(List.of());

            assertThat(result.candlesProcessed()).isZero();
            assertThat(result.levels()).isEmpty();
            assertThat(result.signals()).isEmpty();
        }
    }
}
