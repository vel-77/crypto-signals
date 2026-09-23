package com.cryptosignals.fixtures;

import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.Timeframe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Builders for deterministic synthetic candle series.
 *
 * <p>Series are described as a <em>price path</em> of closes. Candle {@code i} opens at
 * {@code path[i-1]} and closes at {@code path[i]}, with no wicks beyond the body unless a test asks
 * for them. That gives exact control over where swing extremes land: a turning point in the path is
 * the swing price, to the last decimal, which is what makes assertions about touch prices meaningful.
 */
public final class Candles {

    /** Fixed epoch for every fixture, so failures are reproducible and timestamps are readable. */
    public static final long T0 = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();

    private Candles() {
    }

    /**
     * Builds a zig-zag price path.
     *
     * @param start          first close
     * @param candlesPerLeg  candles used to travel to each waypoint
     * @param waypoints      successive turning prices
     */
    public static double[] zigzag(double start, int candlesPerLeg, double... waypoints) {
        if (candlesPerLeg < 1) {
            throw new IllegalArgumentException("candlesPerLeg must be >= 1");
        }
        List<Double> path = new ArrayList<>();
        path.add(start);
        double from = start;
        for (double to : waypoints) {
            for (int step = 1; step <= candlesPerLeg; step++) {
                path.add(from + (to - from) * step / candlesPerLeg);
            }
            from = to;
        }
        double[] out = new double[path.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = path.get(i);
        }
        return out;
    }

    /** Scales an entire path, used to prove the engine is price-scale independent. */
    public static double[] scale(double[] path, double factor) {
        double[] out = new double[path.length];
        for (int i = 0; i < path.length; i++) {
            out[i] = path[i] * factor;
        }
        return out;
    }

    public static List<Candle> fromPath(Timeframe timeframe, double[] path) {
        return fromPath(timeframe, T0, path);
    }

    /** Body-only candles: high and low are the body edges, so turning points are exact extremes. */
    public static List<Candle> fromPath(Timeframe timeframe, long startTime, double[] path) {
        List<Candle> candles = new ArrayList<>(path.length);
        long step = timeframe.durationMillis();
        for (int i = 0; i < path.length; i++) {
            double open = i == 0 ? path[0] : path[i - 1];
            double close = path[i];
            long openTime = startTime + (long) i * step;
            candles.add(new Candle(openTime, openTime + step - 1, open,
                    Math.max(open, close), Math.min(open, close), close, 1.0));
        }
        return candles;
    }

    /** A single explicit candle, for tests that need a precise wick. */
    public static Candle candle(Timeframe timeframe, long openTime,
                                double open, double high, double low, double close) {
        return new Candle(openTime, openTime + timeframe.durationMillis() - 1,
                open, high, low, close, 1.0);
    }

    /** Appends a candle that continues the series one step after the last one. */
    public static Candle next(Timeframe timeframe, List<Candle> series,
                              double open, double high, double low, double close) {
        long openTime = series.get(series.size() - 1).openTime() + timeframe.durationMillis();
        return candle(timeframe, openTime, open, high, low, close);
    }

    /** Appends {@code count} flat candles resting exactly at {@code price}. */
    public static void appendFlat(Timeframe timeframe, List<Candle> series, double price, int count) {
        for (int i = 0; i < count; i++) {
            series.add(next(timeframe, series, price, price, price, price));
        }
    }
}
