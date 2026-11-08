package com.cryptosignals.analysis;

import com.cryptosignals.analysis.target.NextPotential;
import com.cryptosignals.analysis.target.NextPotentialFinder;
import com.cryptosignals.analysis.target.NextPotentialFinder.Swing;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.fixtures.Candles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NextPotentialFinderTest {

    private static final Timeframe TF = Timeframe.M15;
    private static final AnalysisConfig CONFIG = AnalysisConfig.builder().build();
    private static final double MIN = 1.0;
    private static final long NO_LIMIT = 0;

    /** Runs the rule as of the last candle of the series, the way a signal on that candle sees it. */
    private static Optional<NextPotential> at(List<Candle> series, LevelType type, double price,
                                              long lookbackMillis, double minDistance) {
        List<Swing> swings = NextPotentialFinder.swings(series, CONFIG);
        long signalTime = series.get(series.size() - 1).openTime();
        return NextPotentialFinder.find(swings, type, price, signalTime, lookbackMillis, minDistance);
    }

    private static Optional<NextPotential> at(List<Candle> series, LevelType type, double price) {
        return at(series, type, price, NO_LIMIT, MIN);
    }

    private static List<Candle> path(double start, double... waypoints) {
        return Candles.fromPath(TF, Candles.zigzag(start, 6, waypoints));
    }

    @Test
    @DisplayName("support example: lows 95, 93, 98, 96, 98.5 below a 100 support select 98.5 (-1.5%)")
    void supportExample() {
        List<Candle> series = path(102, 95, 104, 93, 104, 98, 104, 96, 104, 98.5, 104,
                100, 104, 100, 104, 100, 103);

        assertThat(at(series, LevelType.SUPPORT, 100)).hasValueSatisfying(next -> {
            assertThat(next.price()).isEqualTo(98.5);
            assertThat(next.percent()).isCloseTo(-1.5, within(1e-9));
        });
    }

    @Test
    @DisplayName("resistance example: peaks 105, 107, 102, 104, 101.5 above a 100 resistance select 101.5 (+1.5%)")
    void resistanceExample() {
        List<Candle> series = path(95, 105, 96, 107, 96, 102, 96, 104, 96, 101.5, 96,
                100, 96, 100, 96, 100, 97);

        assertThat(at(series, LevelType.RESISTANCE, 100)).hasValueSatisfying(next -> {
            assertThat(next.price()).isEqualTo(101.5);
            assertThat(next.percent()).isCloseTo(1.5, within(1e-9));
        });
    }

    @Test
    @DisplayName("lows less than the minimum below the signal price are ignored")
    void ignoresSwingsTooClose() {
        List<Candle> series = path(102, 97, 104, 99.2, 104, 100, 104, 100, 103);

        assertThat(at(series, LevelType.SUPPORT, 100)).hasValueSatisfying(next ->
                assertThat(next.price()).isEqualTo(97.0));
    }

    @Test
    @DisplayName("a swing exactly the minimum away qualifies")
    void minimumIsInclusive() {
        List<Candle> series = path(102, 97, 104, 99, 104, 100, 103);

        assertThat(at(series, LevelType.SUPPORT, 100)).hasValueSatisfying(next ->
                assertThat(next.price()).isEqualTo(99.0));
    }

    @Test
    @DisplayName("N/A when no swing is far enough beyond the signal price")
    void nothingQualifies() {
        // Every low is above the support or within 1% of it.
        List<Candle> series = path(102, 99.5, 104, 101, 104, 100, 104, 100, 103);

        assertThat(at(series, LevelType.SUPPORT, 100)).isEmpty();
        assertThat(at(series, LevelType.RESISTANCE, 104)).isEmpty();
    }

    @Test
    @DisplayName("support looks at swing lows only, resistance at swing highs only")
    void usesTheMatchingSwingKind() {
        // A peak at 97 sits between the support and the 95 low; it is a high, so it does not count.
        List<Candle> series = path(90, 97, 95, 104, 100, 104, 100, 103);

        assertThat(at(series, LevelType.SUPPORT, 100)).hasValueSatisfying(next ->
                assertThat(next.price()).isEqualTo(95.0));
    }

    @Test
    @DisplayName("a swing not yet confirmed when the signal fired is not used")
    void noLookAhead() {
        // A 98.5 low prints, then price climbs back. Its right-hand candles confirm it 3 candles later.
        List<Candle> series = path(102, 96, 104, 98.5, 104);
        int trough = 3 * 6;
        assertThat(series.get(trough).low()).isEqualTo(98.5);
        int confirming = trough + CONFIG.swingRightBars();

        List<Swing> swings = NextPotentialFinder.swings(series, CONFIG);
        long before = series.get(confirming - 1).openTime();
        long on = series.get(confirming).openTime();

        assertThat(NextPotentialFinder.find(swings, LevelType.SUPPORT, 100, before, NO_LIMIT, MIN))
                .hasValueSatisfying(next -> assertThat(next.price()).isEqualTo(96.0));
        assertThat(NextPotentialFinder.find(swings, LevelType.SUPPORT, 100, on, NO_LIMIT, MIN))
                .hasValueSatisfying(next -> assertThat(next.price()).isEqualTo(98.5));
    }

    @Test
    @DisplayName("swings older than the lookback are not used")
    void respectsLookback() {
        List<Candle> series = path(102, 98.5, 104, 96, 104, 100, 104, 100, 103);
        long signalTime = series.get(series.size() - 1).openTime();
        long troughTime = series.get(6).openTime();
        assertThat(series.get(6).low()).isEqualTo(98.5);

        long justCovers = signalTime - troughTime;
        assertThat(at(series, LevelType.SUPPORT, 100, justCovers, MIN)).hasValueSatisfying(next ->
                assertThat(next.price()).isEqualTo(98.5));
        assertThat(at(series, LevelType.SUPPORT, 100, justCovers - 1, MIN)).hasValueSatisfying(next ->
                assertThat(next.price()).isEqualTo(96.0));
    }

    @Test
    @DisplayName("of two swings at the same price the more recent one is reported")
    void tieGoesToTheMoreRecentSwing() {
        List<Candle> series = path(102, 98.5, 104, 98.5, 104, 100, 103);

        assertThat(at(series, LevelType.SUPPORT, 100)).hasValueSatisfying(next ->
                assertThat(next.swingTime()).isEqualTo(series.get(3 * 6).openTime()));
    }

    @Test
    @DisplayName("the minimum distance is configurable")
    void minimumIsConfigurable() {
        List<Candle> series = path(102, 97, 104, 98.5, 104, 100, 103);

        assertThat(at(series, LevelType.SUPPORT, 100, NO_LIMIT, 2.0)).hasValueSatisfying(next ->
                assertThat(next.price()).isEqualTo(97.0));
        assertThat(at(series, LevelType.SUPPORT, 100, NO_LIMIT, 0.5)).hasValueSatisfying(next ->
                assertThat(next.price()).isEqualTo(98.5));
    }

    @Test
    @DisplayName("the answer does not depend on the price scale")
    void scaleIndependent() {
        double[] base = Candles.zigzag(95, 6, 105, 96, 107, 96, 101.5, 96, 100, 97);
        double factor = 0.000123;
        List<Candle> scaled = Candles.fromPath(TF, Candles.scale(base, factor));

        assertThat(at(scaled, LevelType.RESISTANCE, 100 * factor)).hasValueSatisfying(next ->
                assertThat(next.percent()).isCloseTo(1.5, within(1e-6)));
    }
}
