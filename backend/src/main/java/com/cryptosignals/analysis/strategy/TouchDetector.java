package com.cryptosignals.analysis.strategy;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.engine.LevelState;
import com.cryptosignals.analysis.engine.TouchEvent;
import com.cryptosignals.domain.Candle;

/**
 * Decides what a single candle does to a level's touch state — in particular, when a new
 * interaction counts as a genuinely <em>separate</em> test of the level.
 */
public interface TouchDetector {

    String name();

    /** Feeds one candle to one level, mutating its touch state machine. */
    TouchEvent onCandle(LevelState level, Candle candle, int index, AnalysisConfig config);
}
