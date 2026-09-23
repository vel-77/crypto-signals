package com.cryptosignals.exchange;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding-window limiter for exchanges that price requests in "weight per minute".
 *
 * <p>A plain requests-per-second limiter is the wrong shape here: on Binance a 1000-candle kline
 * page costs more than a server-time ping, and the published budget is per minute. This tracks the
 * actual weight spent in the trailing window and blocks until enough of it has aged out, which
 * keeps a long backfill running at full speed right up to the limit and then smoothly throttles
 * instead of being banned.
 *
 * <p>Thread-safe; backfill and reconciliation share one instance per exchange client.
 */
public final class WeightedRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(WeightedRateLimiter.class);

    private record Spend(long atMillis, int weight) {
    }

    private final int maxWeightPerWindow;
    private final long windowMillis;
    private final Deque<Spend> spends = new ArrayDeque<>();
    private int currentWeight;

    public WeightedRateLimiter(int maxWeightPerWindow, Duration window) {
        if (maxWeightPerWindow < 1) {
            throw new IllegalArgumentException("maxWeightPerWindow must be >= 1");
        }
        this.maxWeightPerWindow = maxWeightPerWindow;
        this.windowMillis = window.toMillis();
    }

    /** Blocks until {@code weight} fits inside the budget, then records it as spent. */
    public void acquire(int weight) throws InterruptedException {
        synchronized (this) {
            while (true) {
                long now = System.currentTimeMillis();
                evictExpired(now);
                if (currentWeight + weight <= maxWeightPerWindow) {
                    spends.addLast(new Spend(now, weight));
                    currentWeight += weight;
                    return;
                }
                long waitFor = spends.peekFirst().atMillis() + windowMillis - now;
                if (waitFor <= 0) {
                    continue;
                }
                log.debug("Rate limit reached ({}/{}), pausing {} ms",
                        currentWeight, maxWeightPerWindow, waitFor);
                wait(waitFor);
            }
        }
    }

    /**
     * Records weight the exchange told us we spent, reconciling our estimate with the truth in the
     * response headers. Never lowers the recorded total: under-counting is what gets an IP banned.
     */
    public synchronized void reconcile(int reportedWeight) {
        long now = System.currentTimeMillis();
        evictExpired(now);
        if (reportedWeight > currentWeight) {
            int delta = reportedWeight - currentWeight;
            spends.addLast(new Spend(now, delta));
            currentWeight = reportedWeight;
        }
    }

    /** Hard pause, for when the venue explicitly says to back off (HTTP 429 / 418). */
    public synchronized void penalise(Duration duration) {
        long until = System.currentTimeMillis() + duration.toMillis();
        log.warn("Exchange asked us to back off for {} ms", duration.toMillis());
        while (System.currentTimeMillis() < until) {
            try {
                wait(Math.max(1, until - System.currentTimeMillis()));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public synchronized int currentWeight() {
        evictExpired(System.currentTimeMillis());
        return currentWeight;
    }

    private void evictExpired(long now) {
        while (!spends.isEmpty() && now - spends.peekFirst().atMillis() >= windowMillis) {
            currentWeight -= spends.removeFirst().weight();
        }
        if (spends.isEmpty()) {
            currentWeight = 0;
        }
        notifyAll();
    }
}
