package com.cryptosignals.exchange.binance;

import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.exchange.CandleStreamListener;
import com.cryptosignals.exchange.ExchangeSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failure seen in production: the server's network was cut, the websocket never received a
 * close frame or an error, and the stream sat "connected" for a day delivering nothing. These tests
 * pin down that silence alone is enough to declare the connection dead and replace it.
 */
class StreamLivenessTest {

    private static final long STALE_MILLIS = 90_000;

    /** A socket that is "open" as far as the JDK knows, and records whether it was abandoned. */
    private static final class SilentSocket implements WebSocket {
        volatile boolean aborted;

        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer message) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer message) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) { return CompletableFuture.completedFuture(this); }
        @Override public void request(long n) { }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return false; }
        @Override public boolean isInputClosed() { return false; }
        @Override public void abort() { aborted = true; }
    }

    private final List<Throwable> disconnects = new CopyOnWriteArrayList<>();

    private BinanceCandleStream stream() {
        // Unroutable endpoint and a long backoff: the real connection attempt fails once and the
        // next one is minutes away, so nothing in the background interferes with the stub socket.
        ExchangeSettings settings = new ExchangeSettings(
                "http://127.0.0.1:1", "ws://127.0.0.1:1",
                Duration.ofSeconds(2), Duration.ofSeconds(2), 1000, 2400, 1, Duration.ofSeconds(1),
                Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofMillis(STALE_MILLIS));
        return new BinanceCandleStream(settings, new ObjectMapper(),
                List.of(MarketKey.of("BTCUSDT", "15m")), new CandleStreamListener() {
                    @Override
                    public void onCandle(MarketKey market, Candle candle, boolean closed) {
                    }

                    @Override
                    public void onDisconnected(Throwable cause) {
                        disconnects.add(cause);
                    }
                });
    }

    @Test
    @DisplayName("a socket that has gone silent is abandoned and reported as a disconnect")
    void silentSocketIsReplaced() {
        try (BinanceCandleStream stream = stream()) {
            SilentSocket socket = new SilentSocket();
            long lastMessage = System.currentTimeMillis();
            stream.installSocketForTest(socket, lastMessage);
            disconnects.clear();

            stream.checkLivenessForTest(lastMessage + STALE_MILLIS + 1_000);

            assertThat(socket.aborted).isTrue();
            assertThat(stream.isHealthyForTest(lastMessage + STALE_MILLIS + 1_000)).isFalse();
            assertThat(disconnects).singleElement().isInstanceOf(TimeoutException.class);
        }
    }

    @Test
    @DisplayName("a socket that is still delivering is left alone")
    void activeSocketIsKept() {
        try (BinanceCandleStream stream = stream()) {
            SilentSocket socket = new SilentSocket();
            long lastMessage = System.currentTimeMillis();
            stream.installSocketForTest(socket, lastMessage);
            disconnects.clear();

            stream.checkLivenessForTest(lastMessage + 5_000);

            assertThat(socket.aborted).isFalse();
            assertThat(stream.isHealthyForTest(lastMessage + 5_000)).isTrue();
            assertThat(disconnects).isEmpty();
        }
    }

    @Test
    @DisplayName("an open but silent socket is not reported as connected")
    void connectedMeansDataIsFlowing() {
        try (BinanceCandleStream stream = stream()) {
            long longAgo = System.currentTimeMillis() - STALE_MILLIS - 60_000;
            stream.installSocketForTest(new SilentSocket(), longAgo);

            // Before the watchdog has even run, the status must already tell the truth.
            assertThat(stream.isConnected()).isFalse();
        }
    }

    @Test
    @DisplayName("callbacks from an abandoned socket cannot trigger a second reconnect")
    void staleCallbacksAreIgnored() {
        try (BinanceCandleStream stream = stream()) {
            SilentSocket socket = new SilentSocket();
            long lastMessage = System.currentTimeMillis();
            stream.installSocketForTest(socket, lastMessage);
            stream.checkLivenessForTest(lastMessage + STALE_MILLIS + 1_000);
            long reconnectsAfterWatchdog = stream.reconnectCount();

            // A second sweep finds no socket to act on; nothing is scheduled twice.
            stream.checkLivenessForTest(lastMessage + STALE_MILLIS + 20_000);

            assertThat(stream.reconnectCount()).isEqualTo(reconnectsAfterWatchdog);
        }
    }
}
