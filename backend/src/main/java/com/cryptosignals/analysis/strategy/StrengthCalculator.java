package com.cryptosignals.analysis.strategy;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.engine.LevelState;

/** Scores how meaningful a level is, on a 0..1 scale. */
public interface StrengthCalculator {

    String name();

    double strength(LevelState level, AnalysisConfig config);
}
