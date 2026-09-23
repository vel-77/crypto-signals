package com.cryptosignals.analysis.strategy;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.engine.CandleWindow;
import com.cryptosignals.analysis.model.SwingPoint;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.LevelType;

import java.util.ArrayList;
import java.util.List;

/**
 * Classic fractal/pivot detector: candle {@code p} is a swing high when its high dominates the
 * {@code swingLeftBars} candles before it and the {@code swingRightBars} candles after it.
 *
 * <h2>Plateau handling</h2>
 * The left comparison is <b>strict</b> ({@code >}) and the right is <b>non-strict</b> ({@code >=}).
 * For a run of candles sharing the same high this selects exactly the <em>first</em> one, so a flat
 * top yields one swing rather than none or several. Without this asymmetry the detector would
 * either miss plateaus entirely or emit duplicates, and results would depend on tie-breaking order.
 *
 * <h2>Confirmation latency</h2>
 * A swing at index {@code p} is only knowable at index {@code p + swingRightBars}. The detector
 * therefore reports it when that candle arrives, and records both indices on the
 * {@link SwingPoint}. An outside bar can legitimately be both a swing high and a swing low, so up
 * to two points may be returned for one candle.
 */
public final class FractalSwingDetector implements SwingPointDetector {

    @Override
    public String name() {
        return "fractal";
    }

    @Override
    public List<SwingPoint> confirmedAt(CandleWindow window, int index, AnalysisConfig config) {
        int right = config.swingRightBars();
        int left = config.swingLeftBars();
        int pivot = index - right;

        if (pivot - left < 0 || !window.has(pivot - left) || !window.has(index)) {
            return List.of();
        }

        List<SwingPoint> found = new ArrayList<>(2);
        Candle candidate = window.at(pivot);

        if (dominates(window, pivot, left, right, true)) {
            found.add(new SwingPoint(pivot, candidate.openTime(), candidate.high(),
                    LevelType.RESISTANCE, index));
        }
        if (dominates(window, pivot, left, right, false)) {
            found.add(new SwingPoint(pivot, candidate.openTime(), candidate.low(),
                    LevelType.SUPPORT, index));
        }
        return found;
    }

    private boolean dominates(CandleWindow window, int pivot, int left, int right, boolean high) {
        double value = high ? window.at(pivot).high() : window.at(pivot).low();

        for (int i = pivot - left; i < pivot; i++) {
            double other = high ? window.at(i).high() : window.at(i).low();
            if (high ? value <= other : value >= other) {
                return false;
            }
        }
        for (int i = pivot + 1; i <= pivot + right; i++) {
            double other = high ? window.at(i).high() : window.at(i).low();
            if (high ? value < other : value > other) {
                return false;
            }
        }
        return true;
    }
}
