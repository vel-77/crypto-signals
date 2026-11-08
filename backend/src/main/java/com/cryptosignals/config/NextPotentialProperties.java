package com.cryptosignals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;

/**
 * The next potential low / high shown with every signal: the nearest earlier swing low below a
 * support signal (swing high above a resistance signal) at least {@link #minDistancePercent} away.
 */
@ConfigurationProperties(prefix = "crypto-signals.next-potential")
public class NextPotentialProperties {

    /** Swings closer to the signal price than this, in percent, are ignored. */
    private double minDistancePercent = 1.0;

    /** How far back before the signal to look for swings. Zero means all stored history. */
    private Duration lookback = Duration.ofDays(30);

    public double getMinDistancePercent() { return minDistancePercent; }
    public void setMinDistancePercent(double v) {
        if (!(v >= 0)) {
            throw new IllegalArgumentException("next-potential.min-distance-percent must be >= 0, got " + v);
        }
        this.minDistancePercent = v;
    }
    public Duration getLookback() { return lookback; }
    public void setLookback(Duration v) { this.lookback = v == null ? Duration.ZERO : v; }

    /**
     * Identifies the rule a stored value was worked out under, together with the swing settings
     * that decide what counts as a swing. A stored value with a different key is recomputed, so
     * changing a setting takes effect for every signal on the next run.
     */
    public String ruleKey(int swingLeftBars, int swingRightBars) {
        return String.format(Locale.ROOT, "min=%s;lookback=%s;swing=%d/%d",
                minDistancePercent, lookback, swingLeftBars, swingRightBars);
    }
}
