package com.cryptosignals.analysis.strategy;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.engine.LevelState;
import com.cryptosignals.analysis.model.BreakoutEvent;
import com.cryptosignals.domain.Candle;

/** Decides when a level has genuinely been broken rather than merely pierced. */
public interface BreakoutDetector {

    String name();

    /**
     * Feeds one candle to one level.
     *
     * @return the confirmed breakout, or {@code null} if the level still holds
     */
    BreakoutEvent detect(LevelState level, Candle candle, int index, AnalysisConfig config);
}
