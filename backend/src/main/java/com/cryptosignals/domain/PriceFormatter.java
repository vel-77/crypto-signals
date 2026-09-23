package com.cryptosignals.domain;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Human-readable rendering of prices and timestamps.
 *
 * <p>Formatting has to work across the entire crypto price range without being told the scale. A
 * fixed two-decimal format would print a SHIB level as {@code 0.00}, and a fixed eight-decimal one
 * would print Bitcoin as {@code 100,000.00000000}. Instead the number of decimals is derived from
 * the magnitude so that roughly eight significant digits survive, then trailing zeros are dropped:
 *
 * <pre>
 *   100000      -> 100,000
 *   1.5234      -> 1.5234
 *   0.000123456 -> 0.000123456
 * </pre>
 */
public final class PriceFormatter {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    /** Nine keeps a cent visible on a seven-figure price without padding smaller ones. */
    private static final int SIGNIFICANT_DIGITS = 9;
    private static final int MIN_DECIMALS = 0;
    private static final int MAX_DECIMALS = 12;

    private PriceFormatter() {
    }

    public static String price(double value) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        if (value == 0) {
            return "0";
        }
        int magnitude = (int) Math.floor(Math.log10(Math.abs(value)));
        int decimals = Math.max(MIN_DECIMALS, Math.min(MAX_DECIMALS, SIGNIFICANT_DIGITS - magnitude - 1));

        DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(decimals);
        format.setGroupingUsed(true);
        return format.format(BigDecimal.valueOf(value));
    }

    /** UTC, minute precision — the resolution a candle timestamp actually carries. */
    public static String timestamp(long epochMillis) {
        return TIMESTAMP.format(Instant.ofEpochMilli(epochMillis));
    }

    public static String timestamp(Instant instant) {
        return TIMESTAMP.format(instant);
    }
}
