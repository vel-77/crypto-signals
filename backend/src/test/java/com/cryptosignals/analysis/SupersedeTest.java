package com.cryptosignals.analysis;

import com.cryptosignals.analysis.model.AnalysisResult;
import com.cryptosignals.analysis.model.DetectedLevel;
import com.cryptosignals.analysis.model.TradingSignal;
import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.SignalStatus;
import com.cryptosignals.domain.SupersedeReason;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.fixtures.Candles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The price rule ("nearest level"): once price has moved past a level and a stronger level of the
 * same role sits close by on the far side and is now nearer to price, the passed level is
 * SUPERSEDED.
 *
 * <p>The proximity rule is switched off here ({@link #PRICE_RULE}): in these fixtures both levels
 * are established and 0.3% apart, so with it on A would be grouped under B as soon as it got its
 * third touch, before price ever moved — see {@link NearbyLevelSupersedeTest}.
 *
 * <p>Fixtures mirror the SOL case that motivated the rule (121.59 under 122.0075, 0.34% apart):
 * resistance B forms first at 100 300, resistance A then forms just below it at 100 000, and price
 * finally rises through A — without breaking it — until it is closer to B than to A.
 */
class SupersedeTest {

    private static final AnalysisConfig PRICE_RULE =
            AnalysisConfig.builder().supersedeNearbyLevels(false).build();
    private static final Timeframe TF = Timeframe.M15;
    private static final double A = 100_000;
    private static final double B = 100_300;

    /** B with three touches, then A with three touches, then a rise to {@code finalHigh} and a drift back. */
    private static double[] path(int bTouches, double finalHigh, double... tail) {
        double[] head = new double[bTouches * 2];
        for (int i = 0; i < bTouches; i++) {
            head[i * 2] = B;
            head[i * 2 + 1] = 99_300;
        }
        double[] body = {99_000, A, 99_000, A, 99_000, A, 99_000, finalHigh};
        double[] waypoints = concat(head, body, tail.length == 0 ? new double[]{99_700} : tail);
        return Candles.zigzag(99_300, 15, waypoints);
    }

    private static double[] concat(double[]... parts) {
        return Arrays.stream(parts).flatMapToDouble(Arrays::stream).toArray();
    }

    private static AnalysisResult run(double[] path, AnalysisConfig config) {
        return new LevelAnalysisEngineFactory().analyze(new MarketKey("SOLUSDT", TF), config,
                Candles.fromPath(TF, path));
    }

    /**
     * The level of a role nearest to a price, within 0.1%. Levels refine their price as further
     * swings join them (the final rise to 100 180 joins B's cluster), so exact equality is wrong.
     */
    private static DetectedLevel levelNear(AnalysisResult result, LevelType type, double price) {
        return result.levels().stream()
                .filter(l -> l.type() == type && Math.abs(l.price() - price) <= price * 0.001)
                .min(java.util.Comparator.comparingDouble(l -> Math.abs(l.price() - price)))
                .orElseThrow(() -> new AssertionError("no " + type + " level near " + price
                        + "; found " + result.levels().stream()
                        .map(l -> l.type() + "@" + l.price()).toList()));
    }

    private static TradingSignal signalOf(AnalysisResult result, DetectedLevel level) {
        return result.signals().stream()
                .filter(s -> s.levelId().equals(level.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("level at " + level.price() + " never signalled"));
    }

    @Test
    @DisplayName("the passed level is superseded by the stronger level just beyond it")
    void supersedesThePassedLevel() {
        AnalysisResult result = run(path(3, 100_180), PRICE_RULE);

        DetectedLevel passed = levelNear(result, LevelType.RESISTANCE, A);
        DetectedLevel successor = levelNear(result, LevelType.RESISTANCE, B);

        assertThat(passed.status()).isEqualTo(LevelStatus.SUPERSEDED);
        assertThat(passed.supersedeReason()).isEqualTo(SupersedeReason.PRICE_PASSED);
        assertThat(passed.supersededBy()).isEqualTo(successor.id());
        assertThat(passed.supersededByPrice()).isCloseTo(B, within(100.0));
        assertThat(passed.supersededAtTime()).isPositive();
        assertThat(passed.breakoutEvent()).isEmpty();

        assertThat(successor.status()).isEqualTo(LevelStatus.ACTIVE);
        assertThat(signalOf(result, passed).status()).isEqualTo(SignalStatus.SUPERSEDED);
        assertThat(signalOf(result, successor).status()).isEqualTo(SignalStatus.ACTIVE);
    }

    @Test
    @DisplayName("both levels stay active while price has not passed the nearer one")
    void needsPriceToPassTheLevel() {
        // Price reaches into A's zone but closes below A every time.
        AnalysisResult result = run(path(3, 99_950), PRICE_RULE);

        assertThat(levelNear(result, LevelType.RESISTANCE, A).status()).isEqualTo(LevelStatus.ACTIVE);
        assertThat(levelNear(result, LevelType.RESISTANCE, B).status()).isEqualTo(LevelStatus.ACTIVE);
    }

    @Test
    @DisplayName("being just past a level is not enough while it is still the closer one")
    void successorMustBeCloserToPrice() {
        // 100 050 is above A, but 50 from A and 250 from B: A is still the level that matters.
        AnalysisResult result = run(path(3, 100_050), PRICE_RULE);

        assertThat(levelNear(result, LevelType.RESISTANCE, A).status()).isEqualTo(LevelStatus.ACTIVE);
    }

    @Test
    @DisplayName("a level further away than the maximum supersede distance does not take over")
    void respectsMaximumDistance() {
        // The two levels are 0.3% apart; with a 0.2% limit they are unrelated structures.
        AnalysisResult result = run(path(3, 100_180),
                PRICE_RULE.toBuilder().supersedeMaxDistancePercent(0.2).build());

        assertThat(levelNear(result, LevelType.RESISTANCE, A).status()).isEqualTo(LevelStatus.ACTIVE);
    }

    @Test
    @DisplayName("a weak level that has not reached the touch threshold cannot retire an established one")
    void successorMustBeEstablished() {
        AnalysisResult result = run(path(2, 100_180), PRICE_RULE);

        assertThat(levelNear(result, LevelType.RESISTANCE, B).touchCount()).isLessThan(3);
        assertThat(levelNear(result, LevelType.RESISTANCE, A).status()).isEqualTo(LevelStatus.ACTIVE);
    }

    @Test
    @DisplayName("the rule can be switched off")
    void canBeDisabled() {
        AnalysisResult result = run(path(3, 100_180),
                PRICE_RULE.toBuilder().supersedeEnabled(false).build());

        assertThat(levelNear(result, LevelType.RESISTANCE, A).status()).isEqualTo(LevelStatus.ACTIVE);
    }

    @Test
    @DisplayName("a superseded level stops counting touches")
    void supersededLevelIsRetired() {
        // After being superseded, price comes back down and tests A again from below.
        AnalysisResult result = run(path(3, 100_180, 99_000, A, 99_000));
        DetectedLevel passed = levelNear(result, LevelType.RESISTANCE, A);

        assertThat(passed.status()).isEqualTo(LevelStatus.SUPERSEDED);
        assertThat(passed.touches()).allSatisfy(t -> assertThat(t.extremeTime())
                .isLessThanOrEqualTo(passed.supersededAtTime()));
    }

    @Test
    @DisplayName("a later breakout is still recorded, and the status stays superseded")
    void recordsLateBreakout() {
        // After being superseded, price rallies through both levels.
        AnalysisResult result = run(path(3, 100_180, 99_700, 101_500), PRICE_RULE);

        DetectedLevel passed = levelNear(result, LevelType.RESISTANCE, A);
        DetectedLevel successor = levelNear(result, LevelType.RESISTANCE, B);

        assertThat(passed.status()).isEqualTo(LevelStatus.SUPERSEDED);
        assertThat(passed.breakoutEvent()).isPresent();
        assertThat(signalOf(result, passed).status()).isEqualTo(SignalStatus.SUPERSEDED);

        assertThat(successor.status()).isEqualTo(LevelStatus.BROKEN);
        assertThat(signalOf(result, successor).status()).isEqualTo(SignalStatus.INVALIDATED);
    }

    @Test
    @DisplayName("support works the same way, downwards")
    void supportMirror() {
        double[] mirrored = Arrays.stream(path(3, 100_180)).map(p -> 200_000 - p).toArray();
        AnalysisResult result = run(mirrored, PRICE_RULE);

        DetectedLevel passed = levelNear(result, LevelType.SUPPORT, 200_000 - A);
        DetectedLevel successor = levelNear(result, LevelType.SUPPORT, 200_000 - B);

        assertThat(passed.status()).isEqualTo(LevelStatus.SUPERSEDED);
        assertThat(passed.supersededBy()).isEqualTo(successor.id());
        assertThat(successor.status()).isEqualTo(LevelStatus.ACTIVE);
    }

    @Test
    @DisplayName("a candle that touches a level and closes past it towards a nearer one raises no signal")
    void passThroughRaisesNoSignal() {
        // B established at 100 300; A has only two touches at 100 000. One wide candle then rises
        // from 99 500 into A's zone — that would be A's third touch — and closes at 100 180, past A
        // and nearer to B. Price went through A without testing it.
        double[] head = {B, 99_300, B, 99_300, B, 99_300, 99_000, A, 99_000, A, 99_000, 99_500};
        java.util.List<com.cryptosignals.domain.Candle> series =
                new java.util.ArrayList<>(Candles.fromPath(TF, Candles.zigzag(99_300, 15, head)));
        series.add(Candles.next(TF, series, 99_500, 100_180, 99_500, 100_180));
        Candles.appendFlat(TF, series, 100_150, 5);

        AnalysisResult result = new LevelAnalysisEngineFactory().analyze(
                new MarketKey("SOLUSDT", TF), AnalysisConfig.defaults(), series);
        DetectedLevel passed = levelNear(result, LevelType.RESISTANCE, A);

        assertThat(passed.status()).isEqualTo(LevelStatus.SUPERSEDED);
        assertThat(passed.touchCount()).isEqualTo(3);
        assertThat(result.signals()).noneMatch(s -> s.levelId().equals(passed.id()));
    }

    @Test
    @DisplayName("the outcome is deterministic")
    void deterministic() {
        double[] p = path(3, 100_180);
        assertThat(run(p, PRICE_RULE)).isEqualTo(run(p, PRICE_RULE));
    }

    private static AnalysisResult run(double[] path) {
        return run(path, PRICE_RULE);
    }
}
