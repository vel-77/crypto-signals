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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** How often every connection is checked for silence. */
    private static final long LIVENESS_CHECK_MILLIS = 15_000;

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
    private final long staleAfterMillis;

    private volatile boolean closed;

    public BinanceCandleStream(ExchangeSettings settings, ObjectMapper mapper,
                               List<MarketKey> markets, CandleStreamListener listener) {
        this.settings = settings;
        this.mapper = mapper;
        this.listener = listener;
        this.staleAfterMillis = settings.streamStaleAfter().toMillis();
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
        scheduler.scheduleWithFixedDelay(this::checkLiveness,
                LIVENESS_CHECK_MILLIS, LIVENESS_CHECK_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void checkLiveness() {
        long now = System.currentTimeMillis();
        for (Connection connection : connections) {
            try {
                connection.checkLiveness(now);
            } catch (RuntimeException ex) {
                // The watchdog must keep running whatever one connection does.
                log.warn("Websocket liveness check failed: {}", ex.toString());
            }
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
        // Connected means data is actually arriving, not merely that a socket object exists: a
        // silently dead connection must not be reported as healthy.
        long now = System.currentTimeMillis();
        return connections.stream().allMatch(connection -> connection.isHealthy(now));
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

    /**
     * One websocket connection covering a subset of the subscribed streams.
     *
     * <p>Each socket attempt gets its own {@link SocketListener} tagged with a generation number.
     * When a socket is replaced — reconnect after an error, or the liveness watchdog giving up on a
     * silent one — the generation moves on, and any late callback from the old socket is ignored.
     * Without that, an aborted socket reporting its error after the replacement had already started
     * would schedule a second reconnect and leave two sockets delivering the same candles.
     */
    private final class Connection {

        private final List<String> streams;
        private final AtomicInteger generation = new AtomicInteger();
        private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
        private volatile WebSocket socket;
        private volatile long backoffMillis;
        /** Wall-clock time of the last message received, or of the last successful connect. */
        private volatile long lastMessageAt;

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
            reconnectScheduled.set(false);
            int attempt = generation.incrementAndGet();
            http.newWebSocketBuilder()
                    .connectTimeout(settings.connectTimeout())
                    .buildAsync(uri(), new SocketListener(attempt))
                    .whenComplete((webSocket, error) -> {
                        if (attempt != generation.get() || closed) {
                            if (webSocket != null) {
                                webSocket.abort();
                            }
                            return;
                        }
                        if (error != null) {
                            log.warn("Binance websocket connect failed: {}", error.toString());
                            scheduleReconnect();
                            return;
                        }
                        lastMessageAt = System.currentTimeMillis();
                        socket = webSocket;
                        // A connection that opened is assumed healthy; the backoff starts over so a
                        // single blip does not leave us throttled for minutes afterwards.
                        backoffMillis = settings.reconnectMinBackoff().toMillis();
                        log.info("Binance websocket connected with {} stream(s)", streams.size());
                        listener.onConnected();
                    });
        }

        private void scheduleReconnect() {
            if (closed || !reconnectScheduled.compareAndSet(false, true)) {
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

        /**
         * Gives up on a socket that has gone quiet.
         *
         * <p>A network that disappears without sending a TCP reset — a provider cutting the
         * server's link, a NAT entry expiring, a middlebox dropping state — leaves the socket
         * looking open forever: no close frame and no error ever arrive, and nothing is written
         * that could fail. Binance pushes an update for every subscribed kline stream every couple
         * of seconds, so prolonged silence is itself the failure signal.
         */
        void checkLiveness(long now) {
            WebSocket current = socket;
            if (current == null || closed) {
                return;
            }
            long silentFor = now - lastMessageAt;
            if (silentFor <= staleAfterMillis) {
                return;
            }
            log.warn("Binance websocket silent for {} s; assuming the connection is dead and reconnecting",
                    silentFor / 1000);
            generation.incrementAndGet();
            socket = null;
            current.abort();
            listener.onDisconnected(new java.util.concurrent.TimeoutException(
                    "no data for " + silentFor + " ms"));
            scheduleReconnect();
        }

        boolean isHealthy(long now) {
            return socket != null && now - lastMessageAt <= staleAfterMillis;
        }

        void closeSocket() {
            generation.incrementAndGet();
            WebSocket current = socket;
            socket = null;
            if (current != null) {
                current.abort();
            }
        }

        /** Callbacks for exactly one socket attempt. */
        private final class SocketListener implements WebSocket.Listener {

            private final int attempt;
            private final StringBuilder textBuffer = new StringBuilder();

            private SocketListener(int attempt) {
                this.attempt = attempt;
            }

            private boolean current() {
                return attempt == generation.get();
            }

            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                if (!current()) {
                    return null;
                }
                lastMessageAt = System.currentTimeMillis();
                // A single logical message can arrive as several frames; accumulate until whole.
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
                if (current()) {
                    log.info("Binance websocket closed ({}): {}", statusCode, reason);
                    listener.onDisconnected(null);
                    scheduleReconnect();
                }
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                if (current()) {
                    log.warn("Binance websocket error: {}", error.toString());
                    listener.onDisconnected(error);
                    scheduleReconnect();
                }
            }
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

    /** Test hook: installs a socket on the first connection as if it had last spoken at the given time. */
    void installSocketForTest(WebSocket webSocket, long lastMessageAt) {
        Connection connection = connections.get(0);
        connection.generation.incrementAndGet();
        connection.socket = webSocket;
        connection.lastMessageAt = lastMessageAt;
    }

    /** Test hook: runs one liveness sweep as of the given time. */
    void checkLivenessForTest(long now) {
        for (Connection connection : connections) {
            connection.checkLiveness(now);
        }
    }

    /** Test hook: whether the first connection is considered healthy at the given time. */
    boolean isHealthyForTest(long now) {
        return connections.get(0).isHealthy(now);
    }

}
