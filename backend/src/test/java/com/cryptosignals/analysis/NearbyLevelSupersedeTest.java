package com.cryptosignals.analysis;

import com.cryptosignals.analysis.engine.LevelAnalysisEngine;
import com.cryptosignals.analysis.model.AnalysisResult;
import com.cryptosignals.analysis.model.DetectedLevel;
import com.cryptosignals.analysis.model.TradingSignal;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.SignalStatus;
import com.cryptosignals.domain.SupersedeReason;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.fixtures.Candles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proximity rule: established levels of one role (three touches or more) whose neighbouring
 * gaps are all within the timeframe's limit form one group, and only the outermost of the group —
 * the highest resistance, the lowest support — stays ACTIVE. The rest are SUPERSEDED, whatever the
 * current price.
 *
 * <p>Resistances are tested from below with troughs at 99 000, so price stays under every level
 * and never breaks one. Groups are built from the top down, the order a market makes lower highs
 * in; a lower level forming first would be broken by the rally to a higher one.
 */
class NearbyLevelSupersedeTest {

    private static final Timeframe TF = Timeframe.M15;
    private static final MarketKey MARKET = new MarketKey("BTCUSDT", TF);
    private static final double TROUGH = 99_000;

    private static final double R1 = 100_000;
    private static final double R2 = 100_400;   // 0.40% above R1
    private static final double R3 = 100_800;   // 0.40% above R2, 0.80% above R1

    /** {@code count} body-only peaks at {@code price}, each followed by a fall back to the trough. */
    private static double[] peaks(double price, int count) {
        double[] out = new double[count * 2];
        for (int i = 0; i < count; i++) {
            out[i * 2] = price;
            out[i * 2 + 1] = TROUGH;
        }
        return out;
    }

    private static List<Candle> series(double[]... legs) {
        double[] waypoints = Arrays.stream(legs).flatMapToDouble(Arrays::stream).toArray();
        return new ArrayList<>(Candles.fromPath(TF, Candles.zigzag(TROUGH, 15, waypoints)));
    }

    private static AnalysisResult run(List<Candle> candles, AnalysisConfig config) {
        return new LevelAnalysisEngineFactory().analyze(MARKET, config, candles);
    }

    private static AnalysisResult run(List<Candle> candles) {
        return run(candles, AnalysisConfig.defaults());
    }

    private static DetectedLevel levelNear(AnalysisResult result, LevelType type, double price) {
        return result.levels().stream()
                .filter(l -> l.type() == type && Math.abs(l.price() - price) <= price * 0.001)
                .min(Comparator.comparingDouble(l -> Math.abs(l.price() - price)))
                .orElseThrow(() -> new AssertionError("no " + type + " level near " + price
                        + "; found " + result.levels().stream()
                        .map(l -> l.type() + "@" + l.price() + "/" + l.status()).toList()));
    }

    private static List<TradingSignal> signalsOf(AnalysisResult result, DetectedLevel level) {
        return result.signals().stream().filter(s -> s.levelId().equals(level.id())).toList();
    }

    private static void assertSupersededBy(DetectedLevel level, DetectedLevel keeper) {
        assertThat(level.status()).isEqualTo(LevelStatus.SUPERSEDED);
        assertThat(level.supersedeReason()).isEqualTo(SupersedeReason.NEARBY_LEVEL);
        assertThat(level.supersededBy()).isEqualTo(keeper.id());
        assertThat(level.breakoutEvent()).isEmpty();
    }

    @Test
    @DisplayName("of close-together resistances only the highest stays active, chaining past the limit")
    void keepsOnlyTheHighestResistance() {
        List<Candle> candles = series(peaks(R3, 3), peaks(R2, 3), peaks(R1, 3));
        AnalysisResult result = run(candles);

        DetectedLevel top = levelNear(result, LevelType.RESISTANCE, R3);
        assertThat(top.status()).isEqualTo(LevelStatus.ACTIVE);
        assertSupersededBy(levelNear(result, LevelType.RESISTANCE, R2), top);
        // 0.80% from R3 — beyond the 0.50% limit — but linked to it through R2.
        assertSupersededBy(levelNear(result, LevelType.RESISTANCE, R1), top);
        assertThat(result.levels())
                .filteredOn(l -> l.type() == LevelType.RESISTANCE && l.status() == LevelStatus.ACTIVE)
                .containsExactly(top);

        // Price never got near any of them: the rule does not depend on where price is.
        assertThat(candles.get(candles.size() - 1).close()).isEqualTo(TROUGH);
        // Supports are a separate family and are left alone.
        assertThat(levelNear(result, LevelType.SUPPORT, TROUGH).status()).isEqualTo(LevelStatus.ACTIVE);
    }

    @Test
    @DisplayName("support is the mirror image: only the lowest stays active")
    void keepsOnlyTheLowestSupport() {
        List<Candle> mirrored = new ArrayList<>(Candles.fromPath(TF, Arrays.stream(
                Candles.zigzag(TROUGH, 15, concat(peaks(R3, 3), peaks(R2, 3), peaks(R1, 3))))
                .map(p -> 200_000 - p).toArray()));
        AnalysisResult result = run(mirrored);

        DetectedLevel bottom = levelNear(result, LevelType.SUPPORT, 200_000 - R3);
        assertThat(bottom.status()).isEqualTo(LevelStatus.ACTIVE);
        assertSupersededBy(levelNear(result, LevelType.SUPPORT, 200_000 - R2), bottom);
        assertSupersededBy(levelNear(result, LevelType.SUPPORT, 200_000 - R1), bottom);
    }

    @Test
    @DisplayName("a gap wider than the limit ends the group")
    void wideGapSplitsGroups() {
        double far = 101_000; // 0.60% above R2
        AnalysisResult result = run(series(peaks(far, 3), peaks(R2, 3), peaks(R1, 3)));

        DetectedLevel lowerKeeper = levelNear(result, LevelType.RESISTANCE, R2);
        assertThat(levelNear(result, LevelType.RESISTANCE, far).status()).isEqualTo(LevelStatus.ACTIVE);
        assertThat(lowerKeeper.status()).isEqualTo(LevelStatus.ACTIVE);
        assertSupersededBy(levelNear(result, LevelType.RESISTANCE, R1), lowerKeeper);
    }

    @Test
    @DisplayName("a gap of exactly the limit is inside it; just over it is not")
    void limitIsInclusive() {
        AnalysisResult atLimit = run(series(peaks(100_500, 3), peaks(R1, 3)));
        assertThat(levelNear(atLimit, LevelType.RESISTANCE, R1).status()).isEqualTo(LevelStatus.SUPERSEDED);

        AnalysisResult overLimit = run(series(peaks(100_510, 3), peaks(R1, 3)));
        assertThat(levelNear(overLimit, LevelType.RESISTANCE, R1).status()).isEqualTo(LevelStatus.ACTIVE);
    }

    @Test
    @DisplayName("levels below the touch threshold neither take part nor link a chain")
    void onlyEstablishedLevelsCount() {
        AnalysisResult result = run(series(peaks(R3, 3), peaks(R2, 2), peaks(R1, 3)));

        assertThat(levelNear(result, LevelType.RESISTANCE, R2).touchCount()).isEqualTo(2);
        assertThat(levelNear(result, LevelType.RESISTANCE, R3).status()).isEqualTo(LevelStatus.ACTIVE);
        assertThat(levelNear(result, LevelType.RESISTANCE, R2).status()).isEqualTo(LevelStatus.ACTIVE);
        // R1 and R3 are 0.80% apart, and the two-touch R2 between them does not join them.
        assertThat(levelNear(result, LevelType.RESISTANCE, R1).status()).isEqualTo(LevelStatus.ACTIVE);
    }

    @Test
    @DisplayName("a level that reaches its third touch next to a higher one is folded in without a signal")
    void newLowerLevelIsFoldedInSilently() {
        AnalysisResult result = run(series(peaks(R2, 3), peaks(R1, 3)));

        DetectedLevel keeper = levelNear(result, LevelType.RESISTANCE, R2);
        DetectedLevel folded = levelNear(result, LevelType.RESISTANCE, R1);
        assertSupersededBy(folded, keeper);
        assertThat(folded.touchCount()).isEqualTo(3);
        assertThat(signalsOf(result, folded)).isEmpty();
        assertThat(signalsOf(result, keeper)).extracting(TradingSignal::status)
                .containsOnly(SignalStatus.ACTIVE);
    }

    @Test
    @DisplayName("price returning to a folded level does not bring a line back there")
    void foldedAreaStaysFolded() {
        AnalysisResult result = run(series(peaks(R2, 3), peaks(R1, 3), peaks(R1, 3)));

        DetectedLevel keeper = levelNear(result, LevelType.RESISTANCE, R2);
        assertThat(result.levels())
                .filteredOn(l -> l.type() == LevelType.RESISTANCE && l.status() == LevelStatus.ACTIVE)
                .containsExactly(keeper);
        assertThat(result.levels())
                .filteredOn(l -> l.type() == LevelType.RESISTANCE && Math.abs(l.price() - R1) < 100)
                .singleElement()
                .satisfies(folded -> assertThat(folded.status()).isEqualTo(LevelStatus.SUPERSEDED));
    }

    @Test
    @DisplayName("when a higher level becomes established later, the lower one's signal is superseded, not invalidated")
    void laterHigherLevelTakesOver() {
        // R2 gets two touches, then R1 gets three and signals. A wick then tags R2 a third time
        // while closing below R1, so nothing is broken — R2 is simply established now.
        List<Candle> candles = series(peaks(R2, 2), peaks(R1, 3), new double[]{99_850});
        candles.add(Candles.next(TF, candles, 99_850, R2, 99_800, 99_800));
        candles.addAll(tail(candles, 99_800, TROUGH, 15));
        AnalysisResult result = run(candles);

        DetectedLevel keeper = levelNear(result, LevelType.RESISTANCE, R2);
        DetectedLevel lower = levelNear(result, LevelType.RESISTANCE, R1);
        assertThat(keeper.status()).isEqualTo(LevelStatus.ACTIVE);
        assertThat(keeper.touchCount()).isEqualTo(3);
        assertSupersededBy(lower, keeper);
        assertThat(signalsOf(result, lower)).isNotEmpty()
                .extracting(TradingSignal::status).containsOnly(SignalStatus.SUPERSEDED);
        assertThat(signalsOf(result, keeper)).extracting(TradingSignal::status)
                .containsOnly(SignalStatus.ACTIVE);
    }

    @Test
    @DisplayName("a superseded level that has since been broken no longer links a chain")
    void brokenLinkDoesNotJoin() {
        // R2 is folded into R3, then price closes well above R2 (breaking it) without reaching
        // R3's zone. When R1 forms afterwards, only R3 is left 0.80% away, so R1 stays.
        AnalysisResult result = run(series(peaks(R3, 3), peaks(R2, 3), new double[]{100_650, TROUGH},
                peaks(R1, 3)));

        DetectedLevel link = levelNear(result, LevelType.RESISTANCE, R2);
        assertThat(link.status()).isEqualTo(LevelStatus.SUPERSEDED);
        assertThat(link.breakoutEvent()).isPresent();
        assertThat(levelNear(result, LevelType.RESISTANCE, R3).status()).isEqualTo(LevelStatus.ACTIVE);
        assertThat(levelNear(result, LevelType.RESISTANCE, R1).status()).isEqualTo(LevelStatus.ACTIVE);
    }

    @Test
    @DisplayName("the distance limit is configurable")
    void respectsConfiguredDistance() {
        AnalysisResult result = run(series(peaks(R2, 3), peaks(R1, 3)),
                AnalysisConfig.builder().supersedeMaxDistancePercent(0.3).build());

        assertThat(levelNear(result, LevelType.RESISTANCE, R1).status()).isEqualTo(LevelStatus.ACTIVE);
        assertThat(levelNear(result, LevelType.RESISTANCE, R2).status()).isEqualTo(LevelStatus.ACTIVE);
    }

    @Test
    @DisplayName("the rule can be switched off on its own")
    void canBeDisabled() {
        AnalysisResult result = run(series(peaks(R3, 3), peaks(R2, 3), peaks(R1, 3)),
                AnalysisConfig.builder().supersedeNearbyLevels(false).build());

        assertThat(result.levels()).filteredOn(l -> l.type() == LevelType.RESISTANCE)
                .extracting(DetectedLevel::status).containsOnly(LevelStatus.ACTIVE);
    }

    @Test
    @DisplayName("a live engine, which drops finished levels, ends in the same state as a backtest")
    void liveMatchesBacktest() {
        List<Candle> candles = series(peaks(R3, 3), peaks(R2, 3), peaks(R1, 3));
        LevelAnalysisEngine live = new LevelAnalysisEngineFactory()
                .create(MARKET, AnalysisConfig.defaults(), false);
        candles.forEach(live::onClosedCandle);

        AnalysisResult backtest = run(candles);
        assertThat(live.activeLevels()).containsExactlyInAnyOrderElementsOf(
                backtest.levels().stream().filter(l -> l.status() == LevelStatus.ACTIVE).toList());
        assertThat(live.activeLevels()).noneMatch(l -> Math.abs(l.price() - R1) < 100);
    }

    @Test
    @DisplayName("the outcome is deterministic")
    void deterministic() {
        List<Candle> candles = series(peaks(R3, 3), peaks(R2, 3), peaks(R1, 3));
        assertThat(run(candles)).isEqualTo(run(candles));
    }

    private static double[] concat(double[]... parts) {
        return Arrays.stream(parts).flatMapToDouble(Arrays::stream).toArray();
    }

    /** Body-only candles walking from {@code from} to {@code to}, continuing {@code series}. */
    private static List<Candle> tail(List<Candle> series, double from, double to, int steps) {
        List<Candle> out = new ArrayList<>();
        List<Candle> view = new ArrayList<>(series);
        double previous = from;
        for (int i = 1; i <= steps; i++) {
            double close = from + (to - from) * i / steps;
            Candle c = Candles.next(TF, view, previous, Math.max(previous, close),
                    Math.min(previous, close), close);
            out.add(c);
            view.add(c);
            previous = close;
        }
        return out;
    }
}
