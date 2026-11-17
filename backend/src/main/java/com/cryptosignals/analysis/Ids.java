package com.cryptosignals.analysis;

import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.MarketKey;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Deterministic identifiers.
 *
 * <p>Re-running the engine over the same candles with the same configuration must produce not only
 * the same levels and signals but the <em>same ids</em> (specification section 12). Ids are
 * therefore derived from content, never from a counter or a random UUID, which also makes
 * persistence naturally idempotent: re-processing a range upserts rather than duplicates.
 */
public final class Ids {

    private Ids() {
    }

    /**
     * A level is uniquely identified by the market, its role, and the candle it was created from.
     * At most one swing high and one swing low can be confirmed per candle, so at most one level
     * of each type can be created per candle, making this tuple collision-free.
     */
    public static String levelId(MarketKey market, LevelType type, long createdAtTime, double anchorPrice) {
        return levelId("", market, type, createdAtTime, anchorPrice);
    }

    /**
     * The same, for one venue. BTCUSDT on spot and on futures can swing to the very same price on
     * the very same candle, and the two levels must still be two rows. An empty namespace gives the
     * ids levels had before venues were told apart, so stored ones stay valid.
     */
    public static String levelId(String namespace, MarketKey market, LevelType type, long createdAtTime,
                                 double anchorPrice) {
        String venue = namespace == null || namespace.isEmpty() ? "" : namespace + "|";
        return uuid("level|" + venue + market.symbol() + "|" + market.timeframe().code() + "|" + type + "|"
                + createdAtTime + "|" + canonicalPrice(anchorPrice));
    }

    /** A signal is uniquely identified by its level and the touch ordinal that triggered it. */
    public static String signalId(String levelId, int touchCount) {
        return uuid("signal|" + levelId + "|" + touchCount);
    }

    /**
     * Scale-independent canonical rendering of a price, so that ids are stable for assets priced at
     * 0.00000123 and at 123456.78 alike.
     */
    public static String canonicalPrice(double price) {
        return String.format(Locale.ROOT, "%.12e", price);
    }

    private static String uuid(String canonical) {
        return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
