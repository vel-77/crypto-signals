package com.cryptosignals.analysis.target;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.engine.CandleWindow;
import com.cryptosignals.analysis.model.SwingPoint;
import com.cryptosignals.analysis.strategy.FractalSwingDetector;
import com.cryptosignals.analysis.strategy.SwingPointDetector;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.LevelType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds a signal's {@link NextPotential}: the next potential low below a support signal, or the next
 * potential high above a resistance signal.
 *
 * <h2>Rule</h2>
 * <ol>
 *   <li>Take every swing of the signal's kind that printed before the signal: swing lows for a
 *       support signal, swing highs for a resistance signal. Swings are found with the same
 *       fractal rule the engine uses to discover levels.</li>
 *   <li>Keep those beyond the signal price by at least {@code minDistancePercent}: lows at least
 *       that far below a support, highs at least that far above a resistance. Smaller swings count
 *       as much as big ones; only the distance matters.</li>
 *   <li>Pick the one nearest the signal price: the highest qualifying low, the lowest qualifying
 *       high. Not the most extreme and not the most recent. Equal prices go to the more recent
 *       swing.</li>
 *   <li>If nothing qualifies, there is no next potential (shown as N/A).</li>
 * </ol>
 *
 * <h2>No look-ahead</h2>
 * A swing counts only if it was already confirmed when the signal fired, i.e. the candle that
 * confirmed it is not later than the signal candle. The answer therefore depends only on prices the
 * signal could have seen, and is the same whenever and however often it is worked out.
 */
public final class NextPotentialFinder {

    /** Same tolerance as the engine's other percentage limits: a gap of exactly the limit qualifies. */
    private static final double EPSILON = 1e-9;

    /**
     * A confirmed swing.
     *
     * @param time          open time of the candle that printed the extreme
     * @param confirmedTime open time of the candle whose arrival confirmed it
     * @param price         the extreme: high of a swing high, low of a swing low
     * @param type          RESISTANCE for swing highs, SUPPORT for swing lows
     */
    public record Swing(long time, long confirmedTime, double price, LevelType type) {
    }

    private NextPotentialFinder() {
    }

    /** Every confirmed swing in a chronological series, found with the engine's fractal rule. */
    public static List<Swing> swings(List<Candle> candles, AnalysisConfig config) {
        return swings(candles, new FractalSwingDetector(), config);
    }

    public static List<Swing> swings(List<Candle> candles, SwingPointDetector detector,
                                     AnalysisConfig config) {
        CandleWindow window = new CandleWindow(config.swingLeftBars() + config.swingRightBars() + 2);
        List<Swing> out = new ArrayList<>();
        for (Candle candle : candles) {
            int index = window.append(candle);
            for (SwingPoint point : detector.confirmedAt(window, index, config)) {
                out.add(new Swing(point.time(), candle.openTime(), point.price(), point.type()));
            }
        }
        return out;
    }

    /**
     * The nearest qualifying swing beyond a signal price.
     *
     * @param swings             candidates, in any order
     * @param signalType         SUPPORT looks for lows below, RESISTANCE for highs above
     * @param signalPrice        the signal's level price
     * @param signalTime         open time of the candle that raised the signal
     * @param lookbackMillis     only swings printed at most this long before the signal count;
     *                           zero or less means no limit
     * @param minDistancePercent the smallest distance from the signal price that qualifies
     */
    public static Optional<NextPotential> find(List<Swing> swings, LevelType signalType,
                                               double signalPrice, long signalTime,
                                               long lookbackMillis, double minDistancePercent) {
        if (!(signalPrice > 0)) {
            return Optional.empty();
        }
        long earliest = lookbackMillis > 0 ? signalTime - lookbackMillis : Long.MIN_VALUE;

        Swing best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Swing swing : swings) {
            if (swing.type() != signalType || swing.confirmedTime() > signalTime
                    || swing.time() < earliest) {
                continue;
            }
            double distance = signalType == LevelType.SUPPORT
                    ? (signalPrice - swing.price()) / signalPrice * 100.0
                    : (swing.price() - signalPrice) / signalPrice * 100.0;
            if (distance + EPSILON < minDistancePercent) {
                continue;
            }
            if (distance < bestDistance || (distance == bestDistance && swing.time() > best.time())) {
                best = swing;
                bestDistance = distance;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        double percent = (best.price() - signalPrice) / signalPrice * 100.0;
        return Optional.of(new NextPotential(best.price(), best.time(), percent));
    }
}
