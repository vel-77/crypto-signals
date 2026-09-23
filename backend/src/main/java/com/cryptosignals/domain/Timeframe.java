package com.cryptosignals.domain;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Supported candlestick timeframes.
 *
 * <p>Adding a new timeframe is a one-line change here: the whole system (analysis, persistence,
 * scheduling, exchange subscription) is driven off {@link #code()} and {@link #durationMillis()},
 * never off hardcoded strings. Codes intentionally match Binance interval codes, but the exchange
 * layer is responsible for translating if another venue uses different ones.
 */
public enum Timeframe {
    M1("1m", Duration.ofMinutes(1)),
    M3("3m", Duration.ofMinutes(3)),
    M5("5m", Duration.ofMinutes(5)),
    M15("15m", Duration.ofMinutes(15)),
    M30("30m", Duration.ofMinutes(30)),
    H1("1h", Duration.ofHours(1)),
    H2("2h", Duration.ofHours(2)),
    H4("4h", Duration.ofHours(4)),
    H6("6h", Duration.ofHours(6)),
    H12("12h", Duration.ofHours(12)),
    D1("1d", Duration.ofDays(1)),
    D3("3d", Duration.ofDays(3)),
    W1("1w", Duration.ofDays(7));

    private static final Map<String, Timeframe> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Timeframe::code, Function.identity()));

    private final String code;
    private final Duration duration;

    Timeframe(String code, Duration duration) {
        this.code = code;
        this.duration = duration;
    }

    public String code() {
        return code;
    }

    public Duration duration() {
        return duration;
    }

    public long durationMillis() {
        return duration.toMillis();
    }

    /** Parses either the exchange code ("5m") or the enum name ("M5"), case-insensitively. */
    public static Timeframe fromCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Timeframe must not be blank");
        }
        String normalized = value.trim().toLowerCase();
        Timeframe byCode = BY_CODE.get(normalized);
        if (byCode != null) {
            return byCode;
        }
        try {
            return Timeframe.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unsupported timeframe '" + value + "'. Supported: " + BY_CODE.keySet());
        }
    }

    /** Number of candles of this timeframe that fit into the given duration (floor). */
    public long candlesIn(Duration period) {
        return period.toMillis() / durationMillis();
    }

    /** Aligns an epoch-millis timestamp down to the open time of the candle containing it. */
    public long alignDown(long epochMillis) {
        return Math.floorDiv(epochMillis, durationMillis()) * durationMillis();
    }
}
