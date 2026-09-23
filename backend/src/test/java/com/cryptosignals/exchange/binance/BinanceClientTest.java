package com.cryptosignals.exchange.binance;

import com.cryptosignals.exchange.ExchangeSettings;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.exchange.CandleStreamListener;
import com.cryptosignals.exchange.StreamSubscription;
import com.cryptosignals.exchange.WeightedRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Nested
    @DisplayName("websocket message handling (no network)")
    class StreamParsing {

        private final List<Candle> received = new CopyOnWriteArrayList<>();
        private final List<Boolean> closedFlags = new CopyOnWriteArrayList<>();

        private BinanceCandleStream streamFor(MarketKey market) {
            // An unroutable host: the constructor starts a connection attempt, which we do not want
            // to succeed. Message handling is driven directly instead.
            ExchangeSettings settings = ExchangeSettings.defaults().withWsBaseUrl("ws://127.0.0.1:1");
            return new BinanceCandleStream(settings, mapper, List.of(market),
                    new CandleStreamListener() {
                        @Override
                        public void onCandle(MarketKey m, Candle candle, boolean isClosed) {
                            received.add(candle);
                            closedFlags.add(isClosed);
                        }
                    });
        }

        @Test
        @DisplayName("builds the documented stream name")
        void streamNaming() {
            assertThat(BinanceCandleStream.streamName(MarketKey.of("BTCUSDT", "5m")))
                    .isEqualTo("btcusdt@kline_5m");
            assertThat(BinanceCandleStream.streamName(MarketKey.of("ethusdt", "4h")))
                    .isEqualTo("ethusdt@kline_4h");
        }

        @Test
        @DisplayName("parses a combined-stream kline event and reports whether it is final")
        void parsesKlineEvent() {
            MarketKey market = MarketKey.of("BTCUSDT", "5m");
            try (BinanceCandleStream stream = streamFor(market)) {
                stream.handleMessageForTest("""
                        {"stream":"btcusdt@kline_5m","data":{"e":"kline","E":1700000300000,"s":"BTCUSDT",
                        "k":{"t":1700000000000,"T":1700000299999,"s":"BTCUSDT","i":"5m",
                        "o":"100.5","c":"101.5","h":"102.0","l":"100.0","v":"12.5","x":false}}}""");
                stream.handleMessageForTest("""
                        {"stream":"btcusdt@kline_5m","data":{"e":"kline","E":1700000600000,"s":"BTCUSDT",
                        "k":{"t":1700000300000,"T":1700000599999,"s":"BTCUSDT","i":"5m",
                        "o":"101.5","c":"103.0","h":"103.5","l":"101.0","v":"9.0","x":true}}}""");

                assertThat(received).hasSize(2);
                assertThat(closedFlags).containsExactly(false, true);
                assertThat(received.get(0).open()).isEqualTo(100.5);
                assertThat(received.get(0).high()).isEqualTo(102.0);
                assertThat(received.get(0).low()).isEqualTo(100.0);
                assertThat(received.get(0).close()).isEqualTo(101.5);
                assertThat(received.get(0).volume()).isEqualTo(12.5);
                assertThat(received.get(0).openTime()).isEqualTo(1700000000000L);
            }
        }

        @Test
        @DisplayName("a malformed or unrelated message never breaks the stream")
        void survivesJunk() {
            MarketKey market = MarketKey.of("BTCUSDT", "5m");
            try (BinanceCandleStream stream = streamFor(market)) {
                stream.handleMessageForTest("not json at all");
                stream.handleMessageForTest("{\"result\":null,\"id\":1}");
                stream.handleMessageForTest("{\"stream\":\"btcusdt@kline_5m\",\"data\":{\"k\":{}}}");
                stream.handleMessageForTest("""
                        {"stream":"btcusdt@kline_5m","data":{"k":{"t":1700000000000,"T":1700000299999,
                        "i":"5m","o":"1","c":"2","h":"3","l":"0.5","v":"1","x":true}}}""");

                assertThat(received).hasSize(1);
            }
        }

        @Test
        @DisplayName("an event for an unsubscribed market is ignored")
        void ignoresUnknownMarkets() {
            try (BinanceCandleStream stream = streamFor(MarketKey.of("BTCUSDT", "5m"))) {
                stream.handleMessageForTest("""
                        {"stream":"dogeusdt@kline_1m","data":{"s":"DOGEUSDT","k":{"t":1700000000000,
                        "T":1700000059999,"i":"1m","o":"1","c":"2","h":"3","l":"0.5","v":"1","x":true}}}""");

                assertThat(received).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("weighted rate limiter")
    class RateLimiting {

        @Test
        @DisplayName("spends within budget without blocking")
        void allowsWithinBudget() throws InterruptedException {
            WeightedRateLimiter limiter = new WeightedRateLimiter(10, Duration.ofSeconds(60));

            for (int i = 0; i < 5; i++) {
                limiter.acquire(2);
            }

            assertThat(limiter.currentWeight()).isEqualTo(10);
        }

        @Test
        @DisplayName("blocks once the budget is exhausted, and releases as the window slides")
        void blocksWhenExhausted() throws InterruptedException {
            WeightedRateLimiter limiter = new WeightedRateLimiter(4, Duration.ofMillis(300));
            limiter.acquire(4);

            long start = System.nanoTime();
            limiter.acquire(4);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMillis).isGreaterThanOrEqualTo(200);
        }

        @Test
        @DisplayName("never lowers its estimate when reconciling with the exchange's own count")
        void reconcileOnlyRaises() throws InterruptedException {
            WeightedRateLimiter limiter = new WeightedRateLimiter(100, Duration.ofSeconds(60));
            limiter.acquire(10);

            limiter.reconcile(5);
            assertThat(limiter.currentWeight()).isEqualTo(10);

            limiter.reconcile(42);
            assertThat(limiter.currentWeight()).isEqualTo(42);
        }
    }
}
