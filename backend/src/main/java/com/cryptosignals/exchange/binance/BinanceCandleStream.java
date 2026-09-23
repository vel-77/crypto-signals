package com.cryptosignals.exchange.binance;

import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.exchange.CandleStreamListener;
import com.cryptosignals.exchange.ExchangeSettings;
import com.cryptosignals.exchange.StreamSubscription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live candle stream over Binance's combined websocket endpoint.
 *
 * <h2>Resilience</h2>
 * A websocket to an exchange is not a reliable pipe: Binance closes every connection after 24 hours
 * by design, and networks fail besides. This class treats disconnection as routine rather than
 * exceptional — it reconnects with exponential backoff between the configured bounds, and the
 * backoff resets once a connection has proved healthy.
 *
 * <p>Reconnecting is not enough on its own, because candles that closed while we were away are
 * simply never delivered. Recovering them is the market-data layer's job: it re-reads the stored
 * high-water mark and backfills the hole over REST. This class therefore only has to guarantee that
 * it comes back, not that it missed nothing.
 *
 * <h2>Connection fan-out</h2>
 * Binance caps streams per connection, so markets are spread across several connections, each with
 * an independent reconnect loop. One failing connection does not silence the others.
 */
public final class BinanceCandleStream implements StreamSubscription {

    private static final Logger log = LoggerFactory.getLogger(BinanceCandleStream.class);

    /** Well below the venue's documented per-connection ceiling, leaving room to grow. */
    private static final int STREAMS_PER_CONNECTION = 200;

    private final ExchangeSettings settings;
    private final ObjectMapper mapper;
    private final CandleStreamListener listener;
    private final Map<String, MarketKey> marketsByStream = new HashMap<>();
    private final ScheduledExecutorService scheduler;
    private final HttpClient http;
    private final List<Connection> connections = new ArrayList<>();
    private final AtomicLong reconnects = new AtomicLong();

    private volatile boolean closed;

    public BinanceCandleStream(ExchangeSettings settings, ObjectMapper mapper,
                               List<MarketKey> markets, CandleStreamListener listener) {
        this.settings = settings;
        this.mapper = mapper;
        this.listener = listener;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "binance-ws-reconnect");
            thread.setDaemon(true);
            return thread;
        });
        this.http = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .build();

        List<String> streamNames = new ArrayList<>();
        for (MarketKey market : markets) {
            String stream = streamName(market);
            marketsByStream.put(stream, market);
            streamNames.add(stream);
        }
        for (int i = 0; i < streamNames.size(); i += STREAMS_PER_CONNECTION) {
            List<String> chunk = streamNames.subList(i,
                    Math.min(i + STREAMS_PER_CONNECTION, streamNames.size()));
            Connection connection = new Connection(List.copyOf(chunk));
            connections.add(connection);
            connection.connect();
        }
    }

    static String streamName(MarketKey market) {
        return market.symbol().toLowerCase() + "@kline_" + market.timeframe().code();
    }

    @Override
    public boolean isConnected() {
        if (connections.isEmpty()) {
            return false;
        }
        return connections.stream().allMatch(connection -> connection.socket != null);
    }

    @Override
    public long reconnectCount() {
        return reconnects.get();
    }

    @Override
    public void close() {
        closed = true;
        for (Connection connection : connections) {
            connection.closeSocket();
        }
        scheduler.shutdownNow();
        log.info("Binance candle stream closed");
    }

    /** One websocket connection covering a subset of the subscribed streams. */
    private final class Connection implements WebSocket.Listener {

        private final List<String> streams;
        private final StringBuilder textBuffer = new StringBuilder();
        private volatile WebSocket socket;
        private volatile long backoffMillis;

        private Connection(List<String> streams) {
            this.streams = streams;
            this.backoffMillis = settings.reconnectMinBackoff().toMillis();
        }

        private URI uri() {
            return URI.create(settings.wsBaseUrl() + "/stream?streams="
                    + String.join("/", streams));
        }

        void connect() {
            if (closed) {
                return;
            }
            http.newWebSocketBuilder()
                    .connectTimeout(settings.connectTimeout())
                    .buildAsync(uri(), this)
                    .whenComplete((webSocket, error) -> {
                        if (error != null) {
                            log.warn("Binance websocket connect failed: {}", error.toString());
                            scheduleReconnect();
                            return;
                        }
                        socket = webSocket;
                        // A connection that opened is assumed healthy; the backoff starts over so a
                        // single blip does not leave us throttled for minutes afterwards.
                        backoffMillis = settings.reconnectMinBackoff().toMillis();
                        log.info("Binance websocket connected with {} stream(s)", streams.size());
                        listener.onConnected();
                    });
        }

        private void scheduleReconnect() {
            if (closed) {
                return;
            }
            socket = null;
            long delay = backoffMillis;
            backoffMillis = Math.min(backoffMillis * 2, settings.reconnectMaxBackoff().toMillis());
            reconnects.incrementAndGet();
            log.info("Reconnecting to Binance websocket in {} ms", delay);
            try {
                scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException ex) {
                // The subscription was closed while a reconnect was pending; nothing to do.
            }
        }

        void closeSocket() {
            WebSocket current = socket;
            socket = null;
            if (current != null) {
                current.abort();
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            // A single logical message can arrive as several frames; accumulate until it is whole.
            textBuffer.append(data);
            if (last) {
                String payload = textBuffer.toString();
                textBuffer.setLength(0);
                handleMessage(payload);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("Binance websocket closed ({}): {}", statusCode, reason);
            listener.onDisconnected(null);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("Binance websocket error: {}", error.toString());
            listener.onDisconnected(error);
            scheduleReconnect();
        }
    }

    /**
     * Parses one combined-stream message.
     *
     * <p>Never throws: a malformed or unexpected message must not tear down a live connection that
     * is otherwise delivering data for every other market.
     */
    private void handleMessage(String payload) {
        try {
            JsonNode root = mapper.readTree(payload);
            JsonNode data = root.has("data") ? root.get("data") : root;
            if (!data.has("k")) {
                return;   // a subscription ack or an unrelated event
            }
            String stream = root.path("stream").asText(null);
            JsonNode kline = data.get("k");

            MarketKey market = stream != null ? marketsByStream.get(stream) : null;
            if (market == null) {
                market = marketsByStream.get(
                        data.path("s").asText("").toLowerCase() + "@kline_" + kline.path("i").asText(""));
            }
            if (market == null) {
                return;
            }

            boolean isClosed = kline.path("x").asBoolean(false);
            Candle candle = new Candle(
                    kline.get("t").asLong(), kline.get("T").asLong(),
                    kline.get("o").asDouble(), kline.get("h").asDouble(),
                    kline.get("l").asDouble(), kline.get("c").asDouble(),
                    kline.get("v").asDouble());

            listener.onCandle(market, candle, isClosed);
        } catch (Exception ex) {
            log.warn("Discarding unparseable websocket message: {}", ex.getMessage());
        }
    }

    /** Package-visible for unit testing the parser without a live socket. */
    void handleMessageForTest(String payload) {
        handleMessage(payload);
    }

}
