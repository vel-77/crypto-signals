package com.cryptosignals.analysis;

import com.cryptosignals.analysis.engine.LevelAnalysisEngine;
import com.cryptosignals.analysis.model.AnalysisResult;
import com.cryptosignals.analysis.strategy.BreakoutDetector;
import com.cryptosignals.analysis.strategy.ConfigurableToleranceCalculator;
import com.cryptosignals.analysis.strategy.ConfirmedBreakoutDetector;
import com.cryptosignals.analysis.strategy.FractalSwingDetector;
import com.cryptosignals.analysis.strategy.LevelDetectionStrategy;
import com.cryptosignals.analysis.strategy.SeparatedInteractionTouchDetector;
import com.cryptosignals.analysis.strategy.StrengthCalculator;
import com.cryptosignals.analysis.strategy.SwingClusterLevelStrategy;
import com.cryptosignals.analysis.strategy.ToleranceCalculator;
import com.cryptosignals.analysis.strategy.TouchDetector;
import com.cryptosignals.analysis.strategy.WeightedStrengthCalculator;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;

/**
 * Builds fully wired engines.
 *
 * <p>The no-argument constructor installs the documented v1 strategies. Every strategy is a
 * constructor argument, so an alternative level-discovery algorithm, touch rule or breakout rule
 * can be dropped in — from a test, or from a Spring bean definition — without the engine or its
 * callers changing.
 */
public final class LevelAnalysisEngineFactory {

    private final LevelDetectionStrategy detectionStrategy;
    private final TouchDetector touchDetector;
    private final BreakoutDetector breakoutDetector;
    private final ToleranceCalculator toleranceCalculator;
    private final StrengthCalculator strengthCalculator;

    public LevelAnalysisEngineFactory() {
        this(new SwingClusterLevelStrategy(new FractalSwingDetector()),
                new SeparatedInteractionTouchDetector(),
                new ConfirmedBreakoutDetector(),
                new ConfigurableToleranceCalculator(),
                new WeightedStrengthCalculator());
    }

    public LevelAnalysisEngineFactory(LevelDetectionStrategy detectionStrategy,
                                      TouchDetector touchDetector,
                                      BreakoutDetector breakoutDetector,
                                      ToleranceCalculator toleranceCalculator,
                                      StrengthCalculator strengthCalculator) {
        this.detectionStrategy = detectionStrategy;
        this.touchDetector = touchDetector;
        this.breakoutDetector = breakoutDetector;
        this.toleranceCalculator = toleranceCalculator;
        this.strengthCalculator = strengthCalculator;
    }

    /**
     * @param retainCompletedLevels keep broken and expired levels in memory for the final report.
     *                              True for backtests, false for long-running live engines where
     *                              completed levels already live in the database.
     */
    public LevelAnalysisEngine create(MarketKey market, AnalysisConfig config,
                                      boolean retainCompletedLevels) {
        return new LevelAnalysisEngine(market, config, detectionStrategy, touchDetector,
                breakoutDetector, toleranceCalculator, strengthCalculator, retainCompletedLevels);
    }

    /** Convenience batch entry point: replay a whole series and return the full report. */
    public AnalysisResult analyze(MarketKey market, AnalysisConfig config, Iterable<Candle> candles) {
        LevelAnalysisEngine engine = create(market, config, true);
        for (Candle candle : candles) {
            engine.onClosedCandle(candle);
        }
        return engine.result();
    }

    public String detectionStrategyName() {
        return detectionStrategy.name();
    }
}
