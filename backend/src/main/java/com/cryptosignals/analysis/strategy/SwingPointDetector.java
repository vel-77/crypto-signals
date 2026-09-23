package com.cryptosignals.analysis.strategy;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.engine.CandleWindow;
import com.cryptosignals.analysis.model.SwingPoint;

import java.util.List;

/** Finds local extremes that become candidate support/resistance anchors. */
public interface SwingPointDetector {

    String name();

    /**
     * Called once per closed candle. Returns swing points that the arrival of candle
     * {@code index} has just <em>confirmed</em> — never a swing that could still be invalidated by
     * a future candle. This is what keeps historical replay and live processing identical.
     */
    List<SwingPoint> confirmedAt(CandleWindow window, int index, AnalysisConfig config);
}
