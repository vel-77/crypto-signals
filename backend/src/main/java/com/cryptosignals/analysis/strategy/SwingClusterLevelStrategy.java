package com.cryptosignals.analysis.strategy;

import com.cryptosignals.analysis.engine.LevelState;
import com.cryptosignals.analysis.model.SwingPoint;

import java.util.List;

/**
 * Default discovery strategy: cluster confirmed swing points into price zones.
 *
 * <ol>
 *   <li>Detect a confirmed swing high (resistance candidate) or swing low (support candidate).</li>
 *   <li>Look for an existing active level of the same role within
 *       {@code clusterTolerancePercent} of the swing price.</li>
 *   <li>If one exists, attach the swing to it — the level's price is re-derived from all its member
 *       swings, so repeated tests sharpen the estimate instead of spawning near-duplicate levels.</li>
 *   <li>Otherwise create a new level anchored at the swing.</li>
 * </ol>
 *
 * <p>When several levels are in range the <em>nearest</em> one wins, which makes the assignment
 * independent of level creation order and therefore deterministic.
 *
 * <p>Broken levels are excluded from matching. A new structure forming at a previously broken price
 * consequently becomes a brand-new level with its own identity and its own touch count, which is
 * how section 10's "may be tracked again" requirement is satisfied without resurrecting stale state.
 */
public final class SwingClusterLevelStrategy implements LevelDetectionStrategy {

    private final SwingPointDetector swingDetector;

    public SwingClusterLevelStrategy(SwingPointDetector swingDetector) {
        this.swingDetector = swingDetector;
    }

    @Override
    public String name() {
        return "swing-cluster/" + swingDetector.name();
    }

    @Override
    public void onCandle(DetectionContext ctx) {
        List<SwingPoint> swings = swingDetector.confirmedAt(ctx.window(), ctx.index(), ctx.config());
        for (SwingPoint swing : swings) {
            LevelState nearest = findNearestInRange(ctx, swing);
            if (nearest != null) {
                ctx.attachSwing(nearest, swing);
            } else {
                ctx.createLevel(swing);
            }
        }
    }

    private LevelState findNearestInRange(DetectionContext ctx, SwingPoint swing) {
        double tolerancePercent = ctx.config().clusterTolerancePercent();
        LevelState best = null;
        double bestDistance = Double.MAX_VALUE;

        for (LevelState level : ctx.activeLevels(swing.type())) {
            double distance = Math.abs(swing.price() - level.price());
            double allowed = level.price() * tolerancePercent / 100.0;
            if (distance <= allowed && distance < bestDistance) {
                best = level;
                bestDistance = distance;
            }
        }
        return best;
    }
}
