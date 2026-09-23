package com.cryptosignals.analysis;

import com.cryptosignals.analysis.engine.CandleWindow;
import com.cryptosignals.analysis.model.SwingPoint;
import com.cryptosignals.analysis.strategy.FractalSwingDetector;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.fixtures.Candles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FractalSwingDetectorTest {

    private final FractalSwingDetector detector = new FractalSwingDetector();
    private final AnalysisConfig config = AnalysisConfig.builder().swingBars(2).build();

    /** Runs a series through the detector and collects every swing it confirms. */
    private List<SwingPoint> detectAll(List<Candle> series) {
        CandleWindow window = new CandleWindow(64);
        List<SwingPoint> found = new ArrayList<>();
        for (Candle candle : series) {
            int index = window.append(candle);
            found.addAll(detector.confirmedAt(window, index, config));
        }
        return found;
    }

    @Test
    @DisplayName("finds the peak of a simple up-then-down move")
    void findsSwingHigh() {
        List<SwingPoint> swings = detectAll(Candles.fromPath(Timeframe.M5,
                new double[]{10, 11, 12, 13, 12, 11, 10}));

        assertThat(swings).filteredOn(s -> s.type() == LevelType.RESISTANCE)
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.index()).isEqualTo(3);
                    assertThat(s.price()).isEqualTo(13.0);
                });
    }

    @Test
    @DisplayName("finds the trough of a simple down-then-up move")
    void findsSwingLow() {
        List<SwingPoint> swings = detectAll(Candles.fromPath(Timeframe.M5,
                new double[]{13, 12, 11, 10, 11, 12, 13}));

        assertThat(swings).filteredOn(s -> s.type() == LevelType.SUPPORT)
                .singleElement()
                .satisfies(s -> assertThat(s.price()).isEqualTo(10.0));
    }

    @Test
    @DisplayName("a swing is only reported once its right-hand bars have printed")
    void reportsSwingOnlyAfterConfirmationLatency() {
        List<SwingPoint> swings = detectAll(Candles.fromPath(Timeframe.M5,
                new double[]{10, 11, 12, 13, 12, 11, 10}));

        SwingPoint high = swings.stream()
                .filter(s -> s.type() == LevelType.RESISTANCE).findFirst().orElseThrow();
        assertThat(high.confirmedAtIndex()).isEqualTo(high.index() + config.swingRightBars());
    }

    @Test
    @DisplayName("a flat top yields exactly one swing, at its first candle")
    void plateauYieldsASingleSwing() {
        // Three candles share the same high; a naive rule would emit none or three.
        List<Candle> series = new ArrayList<>(Candles.fromPath(Timeframe.M5,
                new double[]{10, 11, 12, 13}));
        Candles.appendFlat(Timeframe.M5, series, 13, 2);
        series.addAll(Candles.fromPath(Timeframe.M5,
                series.get(series.size() - 1).openTime() + Timeframe.M5.durationMillis(),
                new double[]{12, 11, 10}));

        List<SwingPoint> highs = detectAll(series).stream()
                .filter(s -> s.type() == LevelType.RESISTANCE).toList();

        assertThat(highs).hasSize(1);
        assertThat(highs.get(0).index()).isEqualTo(3);
        assertThat(highs.get(0).price()).isEqualTo(13.0);
    }

    @Test
    @DisplayName("an outside bar is both a swing high and a swing low")
    void outsideBarProducesBothRoles() {
        List<Candle> series = new ArrayList<>();
        long t = Candles.T0;
        long step = Timeframe.M5.durationMillis();
        double[][] ohlc = {
                {10, 10.5, 9.8, 10.2}, {10.2, 10.6, 9.9, 10.3}, {10.3, 10.7, 10.0, 10.4},
                {10.4, 12.0, 8.0, 10.5},   // the outside bar
                {10.5, 10.8, 10.1, 10.6}, {10.6, 10.9, 10.2, 10.7}, {10.7, 11.0, 10.3, 10.8}
        };
        for (int i = 0; i < ohlc.length; i++) {
            series.add(Candles.candle(Timeframe.M5, t + i * step,
                    ohlc[i][0], ohlc[i][1], ohlc[i][2], ohlc[i][3]));
        }

        List<SwingPoint> atPivot = detectAll(series).stream()
                .filter(s -> s.index() == 3).toList();

        assertThat(atPivot).extracting(SwingPoint::type)
                .containsExactlyInAnyOrder(LevelType.RESISTANCE, LevelType.SUPPORT);
    }

    @Test
    @DisplayName("nothing is reported until enough candles exist on both sides")
    void needsFullWindow() {
        CandleWindow window = new CandleWindow(64);
        List<Candle> series = Candles.fromPath(Timeframe.M5, new double[]{10, 11, 12});

        List<SwingPoint> early = new ArrayList<>();
        for (Candle candle : series) {
            early.addAll(detector.confirmedAt(window, window.append(candle), config));
        }

        assertThat(early).isEmpty();
    }
}
