package com.cryptosignals.marketdata;

import com.cryptosignals.config.MarketProperties;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.exchange.ExchangeClient;
import com.cryptosignals.persistence.entity.MarketEntity;
import com.cryptosignals.persistence.jdbc.CandleGap;
import com.cryptosignals.persistence.jdbc.CandleJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Keeps the stored candle history complete and current.
 *
 * <p>Three jobs, all idempotent because candle writes are upserts keyed on (market, open time):
 * initial backfill, resuming after downtime, and repairing holes. Running any of them twice is
 * harmless, which is what lets the system recover from a crash by simply doing them again.
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    /** Binance's own beginning; used when the configured lookback is "everything available". */
    private static final Instant EXCHANGE_EPOCH = Instant.parse("2017-01-01T00:00:00Z");

    private final ExchangeClient exchange;
    private final CandleJdbcRepository candles;
    private final MarketProperties properties;

    public MarketDataService(ExchangeClient exchange, CandleJdbcRepository candles,
                             MarketProperties properties) {
        this.exchange = exchange;
        this.candles = candles;
        this.properties = properties;
    }

    /** Where analysis should start, given the configured lookback. */
    public Instant analysisStart() {
        return properties.isUnlimitedLookback()
                ? EXCHANGE_EPOCH
                : Instant.now().minus(properties.getLookback());
    }

    /**
     * Brings a market's stored history up to date.
     *
     * <p>Fetches only what is missing, in two directions: backwards if the configured lookback now
     * reaches further into the past than what is stored, and forwards from the newest stored candle
     * to now. A restart after an hour of downtime therefore costs one small request, not a full
     * re-download.
     */
    public int backfill(MarketEntity market, MarketKey key) {
        long marketId = market.getId();
        Instant desiredFrom = analysisStart();
        Instant now = Instant.now();
        int stored = 0;

        var existingFirst = candles.firstOpenTime(marketId);
        var existingLast = candles.lastOpenTime(marketId);

        if (existingFirst.isEmpty()) {
            stored += fetchInto(marketId, key, desiredFrom, now, properties.getMaxCandlesPerBackfill());
        } else {
            if (desiredFrom.isBefore(existingFirst.get())) {
                Instant to = existingFirst.get().minusMillis(key.timeframe().durationMillis());
                stored += fetchInto(marketId, key, desiredFrom, to, properties.getMaxCandlesPerBackfill());
            }
            Instant from = existingLast.orElse(desiredFrom)
                    .plusMillis(key.timeframe().durationMillis());
            if (from.isBefore(now)) {
                stored += fetchInto(marketId, key, from, now, properties.getMaxCandlesPerBackfill());
            }
        }

        log.info("Backfill {}: stored {} candles, {} total in range",
                key, stored, candles.count(marketId));
        return stored;
    }

    /**
     * Repairs holes left by exchange downtime, a dropped websocket, or a failed page.
     *
     * <p>Gaps are found with a single indexed pass over the stored range, then each one is refetched
     * individually rather than re-downloading the whole history.
     */
    public int healGaps(MarketEntity market, MarketKey key) {
        long marketId = market.getId();
        Instant from = analysisStart();
        Instant to = Instant.now();
        List<CandleGap> gaps = candles.findGaps(marketId, key.timeframe(), from, to);
        if (gaps.isEmpty()) {
            return 0;
        }

        long step = key.timeframe().durationMillis();
        int repaired = 0;
        for (CandleGap gap : gaps) {
            log.info("Repairing gap in {}: {} candles missing between {} and {}",
                    key, gap.missingCandles(), gap.afterOpenTime(), gap.beforeOpenTime());
            repaired += fetchInto(marketId, key,
                    gap.afterOpenTime().plusMillis(step),
                    gap.beforeOpenTime().minusMillis(step),
                    (int) Math.min(gap.missingCandles() + 10, properties.getMaxCandlesPerBackfill()));
        }
        return repaired;
    }

    /** Stores a single closed candle arriving from the live stream. */
    public void storeLiveCandle(MarketEntity market, Candle candle) {
        candles.upsert(market.getId(), List.of(candle));
    }

    private int fetchInto(long marketId, MarketKey key, Instant from, Instant to, int maxCandles) {
        if (!from.isBefore(to)) {
            return 0;
        }
        List<Candle> fetched = exchange.fetchCandles(
                key.symbol(), key.timeframe(), from, to, maxCandles);
        if (fetched.isEmpty()) {
            return 0;
        }
        candles.upsert(marketId, fetched);
        return fetched.size();
    }
}
