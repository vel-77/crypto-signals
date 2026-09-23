package com.cryptosignals.analysis.strategy;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.engine.CandleWindow;
import com.cryptosignals.analysis.engine.LevelState;
import com.cryptosignals.analysis.model.SwingPoint;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.MarketKey;

import java.util.List;

/**
 * Everything a {@link LevelDetectionStrategy} is allowed to see and do on a given candle.
 *
 * <p>This is the seam that keeps level <em>discovery</em> replaceable. A strategy may inspect
 * recent candles and existing levels and may create or extend levels; it deliberately cannot touch
 * touch-counting, breakout or signal logic, which the engine owns and applies uniformly to whatever
 * levels the strategy produces.
 */
public interface DetectionContext {

    AnalysisConfig config();

    MarketKey market();

    CandleWindow window();

    /** Absolute index of the candle currently being processed. */
    int index();

    /** Current ATR, or {@link Double#NaN} before warm-up. */
    double atr();

    /** Active (non-broken, non-expired) levels of the given role, in creation order. */
    List<LevelState> activeLevels(LevelType type);

    /**
     * Creates a level anchored at the given swing. The engine seeds it with the swing's own
     * interaction and replays any candles that printed between the swing and now, so the level is
     * immediately consistent with the history it was derived from.
     */
    LevelState createLevel(SwingPoint origin);

    /** Adds a swing to an existing level, re-deriving its price and zone. */
    void attachSwing(LevelState level, SwingPoint swing);
}
