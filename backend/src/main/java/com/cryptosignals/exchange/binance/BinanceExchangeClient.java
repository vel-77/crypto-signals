package com.cryptosignals.exchange.binance;

import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.exchange.CandleStreamListener;
import com.cryptosignals.exchange.ExchangeSettings;
import com.cryptosignals.exchange.ExchangeClient;
import com.cryptosignals.exchange.ExchangeException;
import com.cryptosignals.exchange.StreamSubscription;
import com.cryptosignals.exchange.WeightedRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binance spot implementation of {@link ExchangeClient}.
 *
 * <p>Uses the JDK's own HTTP and WebSocket clients rather than a third-party SDK: the surface we
 * need is two REST endpoints and one stream, and an SDK would drag in its own threading, retry and
 * serialisation opinions that then have to be worked around.
 *
 * <h2>Rate limiting</h2>
 * Requests are metered through a {@link WeightedRateLimiter} before they are sent, and the limiter
 * is then reconciled against the {@code x-mbx-used-weight-1m} header Binance returns, so our
 * estimate can never drift below reality. A 429 or 418 is honoured literally, including the
 * {@code Retry-After} header.
 *
 * <h2>Partial candles</h2>
 * Binance includes the in-progress candle in a klines response. It is dropped here, at the boundary,
 * so nothing downstream ever has to reason about whether a candle is final.
 */
public class BinanceExchangeClient implements ExchangeClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceExchangeClient.class);

    public static final String NAME = "BINANCE";

    /** Published weight of GET /api/v3/klines for page sizes up to 1000. */
    private static final int KLINES_WEIGHT = 2;
    private static final int EXCHANGE_INFO_WEIGHT = 20;
    private static final int SERVER_TIME_WEIGHT = 1;

    private final ExchangeSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final WeightedRateLimiter rateLimiter;
    private final Map<String, Boolean> symbolSupport = new ConcurrentHashMap<>();

    public BinanceExchangeClient(ExchangeSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.rateLimiter = new WeightedRateLimiter(
                settings.maxWeightPerMinute(), Duration.ofMinutes(1));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Candle> fetchCandles(String symbol, Timeframe timeframe,
                                     Instant from, Instant to, int maxCandles) {
        List<Candle> collected = new ArrayList<>();
        long step = timeframe.durationMillis();
        long cursor = timeframe.alignDown(from.toEpochMilli());
        long end = to.toEpochMilli();
        int pageSize = Math.min(settings.klinePageSize(), 1000);

        while (cursor <= end && collected.size() < maxCandles) {
            int limit = Math.min(pageSize, maxCandles - collected.size());
            List<Candle> page = fetchPage(symbol, timeframe, cursor, end, limit);
            if (page.isEmpty()) {
                break;
            }
            for (Candle candle : page) {
                // Paging by open time can re-deliver the boundary candle; keep the series strictly
                // increasing so the engine never sees a duplicate it would have to discard.
                if (collected.isEmpty()
                        || candle.openTime() > collected.get(collected.size() - 1).openTime()) {
                    collected.add(candle);
                }
            }
            long lastOpen = page.get(page.size() - 1).openTime();
            long next = lastOpen + step;
            if (next <= cursor) {
                break;
            }
            cursor = next;
            if (page.size() < limit) {
                break;   // the venue had nothing more to give
            }
        }
        log.debug("Fetched {} candles for {} {} between {} and {}",
                collected.size(), symbol, timeframe.code(), from, to);
        return collected;
    }

    @Override
    public List<Candle> fetchLatestCandles(String symbol, Timeframe timeframe, int limit) {
        String url = settings.restBaseUrl() + "/api/v3/klines"
                + "?symbol=" + encode(symbol)
                + "&interval=" + timeframe.code()
                + "&limit=" + Math.min(limit, 1000);
        return parseKlines(getJson(url, KLINES_WEIGHT));
    }

    private List<Candle> fetchPage(String symbol, Timeframe timeframe, long startTime, long endTime, int limit) {
        String url = settings.restBaseUrl() + "/api/v3/klines"
                + "?symbol=" + encode(symbol)
                + "&interval=" + timeframe.code()
                + "&startTime=" + startTime
                + "&endTime=" + endTime
                + "&limit=" + limit;
        return parseKlines(getJson(url, KLINES_WEIGHT));
    }

    /**
     * Converts Binance's positional kline arrays into candles, dropping anything still forming and
     * anything that fails OHLC sanity. A malformed row is logged and skipped rather than aborting a
     * whole backfill.
     */
    private List<Candle> parseKlines(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<Candle> candles = new ArrayList<>(array.size());
        for (JsonNode row : array) {
            try {
                long openTime = row.get(0).asLong();
                long closeTime = row.get(6).asLong();
                if (closeTime >= now) {
                    continue;   // still forming
                }
                candles.add(new Candle(openTime, closeTime,
                        row.get(1).asDouble(), row.get(2).asDouble(), row.get(3).asDouble(),
                        row.get(4).asDouble(), row.get(5).asDouble()));
            } catch (RuntimeException ex) {
                log.warn("Skipping malformed kline {}: {}", row, ex.getMessage());
            }
        }
        return candles;
    }

    @Override
    public StreamSubscription streamCandles(List<MarketKey> markets, CandleStreamListener listener) {
        return new BinanceCandleStream(settings, mapper, markets, listener);
    }

    @Override
    public Instant serverTime() {
        JsonNode node = getJson(settings.restBaseUrl() + "/api/v3/time", SERVER_TIME_WEIGHT);
        return Instant.ofEpochMilli(node.get("serverTime").asLong());
    }

    @Override
    public boolean supportsSymbol(String symbol) {
        return symbolSupport.computeIfAbsent(symbol.toUpperCase(), key -> {
            try {
                JsonNode node = getJson(settings.restBaseUrl()
                        + "/api/v3/exchangeInfo?symbol=" + encode(key), EXCHANGE_INFO_WEIGHT);
                JsonNode symbols = node.get("symbols");
                return symbols != null && symbols.isArray() && !symbols.isEmpty()
                        && "TRADING".equals(symbols.get(0).path("status").asText());
            } catch (ExchangeException ex) {
                log.warn("Symbol {} is not tradable on Binance: {}", key, ex.getMessage());
                return false;
            }
        });
    }

    /**
     * Performs a metered GET with bounded retry.
     *
     * <p>Only transport failures and 5xx are retried. A 4xx other than a rate-limit response means
     * the request itself is wrong, so retrying it would just burn weight.
     */
    private JsonNode getJson(String url, int weight) {
        ExchangeException last = null;

        for (int attempt = 1; attempt <= settings.maxRetries(); attempt++) {
            try {
                rateLimiter.acquire(weight);
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(settings.readTimeout())
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                reconcileWeight(response);

                int status = response.statusCode();
                if (status == 200) {
                    return mapper.readTree(response.body());
                }
                if (status == 429 || status == 418) {
                    Duration backoff = retryAfter(response)
                            .orElse(settings.retryBackoff().multipliedBy(1L << attempt));
                    rateLimiter.penalise(backoff);
                    last = new ExchangeException("Binance rate limited: HTTP " + status, status, true, null);
                    continue;
                }
                boolean retryable = status >= 500;
                last = new ExchangeException(
                        "Binance returned HTTP " + status + ": " + truncate(response.body()),
                        status, retryable, null);
                if (!retryable) {
                    throw last;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ExchangeException("Interrupted while calling Binance", ex);
            } catch (IOException ex) {
                last = new ExchangeException("Binance request failed: " + ex.getMessage(), -1, true, ex);
            }

            sleepBackoff(attempt);
        }
        throw last != null ? last : new ExchangeException("Binance request failed", null);
    }

    private void reconcileWeight(HttpResponse<String> response) {
        response.headers().firstValue("x-mbx-used-weight-1m").ifPresent(value -> {
            try {
                rateLimiter.reconcile(Integer.parseInt(value.trim()));
            } catch (NumberFormatException ignored) {
                // Header shape changed; our own accounting remains the fallback.
            }
        });
    }

    private java.util.Optional<Duration> retryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After").map(value -> {
            try {
                return Duration.ofSeconds(Long.parseLong(value.trim()));
            } catch (NumberFormatException ex) {
                return settings.retryBackoff();
            }
        });
    }

    private void sleepBackoff(int attempt) {
        long millis = settings.retryBackoff().toMillis() * (1L << Math.min(attempt, 6));
        try {
            Thread.sleep(Math.min(millis, 60_000));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ExchangeException("Interrupted during backoff", ex);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value.toUpperCase(), StandardCharsets.UTF_8);
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }

    /** Exposed for diagnostics and tests. */
    public WeightedRateLimiter rateLimiter() {
        return rateLimiter;
    }
}
