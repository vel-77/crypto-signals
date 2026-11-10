package com.cryptosignals.marketdata;

import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.exchange.CandleStreamListener;
import com.cryptosignals.exchange.ExchangeClient;
import com.cryptosignals.exchange.StreamSubscription;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges the exchange's live stream to the analysis engines.
 *
 * <p>Only closed candles are forwarded. An in-progress candle changes many times before it settles,
 * and feeding those to the engine would mean the same candle is evaluated repeatedly with different
 * values — touches would open and vanish, and the same data would produce different results
 * depending on when it was observed. Partial updates are counted for observability and dropped.
 */
@Component
public class LiveStreamManager implements CandleStreamListener {

    private static final Logger log = LoggerFactory.getLogger(LiveStreamManager.class);

    private final ExchangeClient exchange;
    private final MarketAnalysisService analysisService;

    private final AtomicLong closedCandles = new AtomicLong();
    private final AtomicLong partialCandles = new AtomicLong();
    /** Reconnects of subscriptions replaced by {@link #restart}. */
    private final AtomicLong earlierReconnects = new AtomicLong();
    private volatile StreamSubscription subscription;
    private volatile Instant lastCandleAt;

    public LiveStreamManager(ExchangeClient exchange, MarketAnalysisService analysisService) {
        this.exchange = exchange;
        this.analysisService = analysisService;
    }

    public synchronized void start(List<MarketKey> markets) {
        if (subscription != null) {
            return;
        }
        if (markets.isEmpty()) {
            log.warn("No markets configured; live streaming not started");
            return;
        }
        log.info("Starting live stream for {} market(s)", markets.size());
        subscription = exchange.streamCandles(markets, this);
    }

    /**
     * Replaces the stream with one for exactly these markets, after the watch list changed.
     *
     * <p>A fresh subscription rather than subscribe / unsubscribe messages on the open sockets:
     * the list changes about once a day, and opening the connections again is the path that is
     * exercised on every reconnect anyway. Candles that close while it reconnects are recovered
     * like after any other reconnect (see {@link MarketAnalysisService#ingestLiveCandle}).
     */
    public synchronized void restart(List<MarketKey> markets) {
        if (subscription != null) {
            earlierReconnects.addAndGet(subscription.reconnectCount());
            subscription.close();
            subscription = null;
        }
        if (markets.isEmpty()) {
            log.warn("No markets left to watch; live streaming stopped");
            return;
        }
        log.info("Restarting live stream for {} market(s)", markets.size());
        subscription = exchange.streamCandles(markets, this);
    }

    @Override
    public void onCandle(MarketKey market, Candle candle, boolean closed) {
        if (!closed) {
            partialCandles.incrementAndGet();
            return;
        }
        closedCandles.incrementAndGet();
        lastCandleAt = Instant.now();
        analysisService.ingestLiveCandle(market, candle);
    }

    @Override
    public void onConnected() {
        log.info("Live stream connected");
    }

    @Override
    public void onDisconnected(Throwable cause) {
        log.warn("Live stream disconnected{}", cause == null ? "" : ": " + cause);
    }

    public boolean isRunning() {
        return subscription != null;
    }

    public boolean isConnected() {
        StreamSubscription current = subscription;
        return current != null && current.isConnected();
    }

    public long reconnectCount() {
        StreamSubscription current = subscription;
        return earlierReconnects.get() + (current == null ? 0 : current.reconnectCount());
    }

    public long closedCandlesReceived() {
        return closedCandles.get();
    }

    public long partialCandlesDropped() {
        return partialCandles.get();
    }

    public Instant lastCandleAt() {
        return lastCandleAt;
    }

    @PreDestroy
    public synchronized void stop() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }
}
