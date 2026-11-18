package com.cryptosignals.marketdata;

import com.cryptosignals.config.Venue;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.exchange.CandleStreamListener;
import com.cryptosignals.exchange.StreamSubscription;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges each market's live stream to the analysis engines. Every market (spot, futures) has its
 * own stream, with its own connections and its own health.
 *
 * <p>Only closed candles are forwarded. An in-progress candle changes many times before it settles,
 * and feeding those to the engine would mean the same candle is evaluated repeatedly with different
 * values — touches would open and vanish, and the same data would produce different results
 * depending on when it was observed. Partial updates are counted for observability and dropped.
 */
@Component
public class LiveStreamManager {

    private static final Logger log = LoggerFactory.getLogger(LiveStreamManager.class);

    private final MarketAnalysisService analysisService;
    private final Map<String, Feed> feeds = new ConcurrentHashMap<>();

    public LiveStreamManager(MarketAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /** One market's stream and what it has delivered. */
    private final class Feed implements CandleStreamListener {

        private final Venue venue;
        private final AtomicLong closedCandles = new AtomicLong();
        private final AtomicLong partialCandles = new AtomicLong();
        /** Reconnects of subscriptions replaced by {@link #restart}. */
        private final AtomicLong earlierReconnects = new AtomicLong();
        private volatile StreamSubscription subscription;
        private volatile Instant lastCandleAt;

        private Feed(Venue venue) {
            this.venue = venue;
        }

        @Override
        public void onCandle(MarketKey market, Candle candle, boolean closed) {
            if (!closed) {
                partialCandles.incrementAndGet();
                return;
            }
            closedCandles.incrementAndGet();
            lastCandleAt = Instant.now();
            analysisService.ingestLiveCandle(venue.name(), market, candle);
        }

        @Override
        public void onConnected() {
            log.info("Live stream connected ({})", venue.label());
        }

        @Override
        public void onDisconnected(Throwable cause) {
            log.warn("Live stream disconnected ({}){}", venue.label(), cause == null ? "" : ": " + cause);
        }
    }

    private Feed feed(Venue venue) {
        return feeds.computeIfAbsent(venue.name(), name -> new Feed(venue));
    }

    public synchronized void start(Venue venue, List<MarketKey> markets) {
        Feed feed = feed(venue);
        if (feed.subscription != null) {
            return;
        }
        if (markets.isEmpty()) {
            log.warn("No markets configured on {}; live streaming not started", venue.label());
            return;
        }
        log.info("Starting live stream for {} market(s) on {}", markets.size(), venue.label());
        feed.subscription = venue.client().streamCandles(markets, feed);
    }

    /**
     * Replaces one market's stream with one for exactly these markets, after its watch list changed.
     *
     * <p>A fresh subscription rather than subscribe / unsubscribe messages on the open sockets:
     * the list changes about once a day, and opening the connections again is the path that is
     * exercised on every reconnect anyway. Candles that close while it reconnects are recovered
     * like after any other reconnect (see {@link MarketAnalysisService#ingestLiveCandle}).
     */
    public synchronized void restart(Venue venue, List<MarketKey> markets) {
        Feed feed = feed(venue);
        if (feed.subscription != null) {
            feed.earlierReconnects.addAndGet(feed.subscription.reconnectCount());
            feed.subscription.close();
            feed.subscription = null;
        }
        if (markets.isEmpty()) {
            log.warn("No markets left to watch on {}; live streaming stopped", venue.label());
            return;
        }
        log.info("Restarting live stream for {} market(s) on {}", markets.size(), venue.label());
        feed.subscription = venue.client().streamCandles(markets, feed);
    }

    public boolean isRunning(Venue venue) {
        Feed feed = feeds.get(venue.name());
        return feed != null && feed.subscription != null;
    }

    public boolean isConnected(Venue venue) {
        Feed feed = feeds.get(venue.name());
        StreamSubscription current = feed == null ? null : feed.subscription;
        return current != null && current.isConnected();
    }

    public long reconnectCount(Venue venue) {
        Feed feed = feeds.get(venue.name());
        if (feed == null) {
            return 0;
        }
        StreamSubscription current = feed.subscription;
        return feed.earlierReconnects.get() + (current == null ? 0 : current.reconnectCount());
    }

    public long closedCandlesReceived(Venue venue) {
        Feed feed = feeds.get(venue.name());
        return feed == null ? 0 : feed.closedCandles.get();
    }

    public long partialCandlesDropped(Venue venue) {
        Feed feed = feeds.get(venue.name());
        return feed == null ? 0 : feed.partialCandles.get();
    }

    public Instant lastCandleAt(Venue venue) {
        Feed feed = feeds.get(venue.name());
        return feed == null ? null : feed.lastCandleAt;
    }

    @PreDestroy
    public synchronized void stop() {
        for (Feed feed : feeds.values()) {
            if (feed.subscription != null) {
                feed.subscription.close();
                feed.subscription = null;
            }
        }
    }
}
