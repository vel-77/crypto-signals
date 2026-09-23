package com.cryptosignals.persistence.jdbc;

import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Candle storage.
 *
 * <p>Deliberately plain JDBC rather than JPA. Candles are written in large append-only batches and
 * read as ordered ranges; an entity manager would add per-row object tracking and a first-level
 * cache that only get in the way at this volume.
 */
@Repository
public class CandleJdbcRepository {

    private static final Logger log = LoggerFactory.getLogger(CandleJdbcRepository.class);

    /** Rows pulled per server-side cursor round trip when streaming. */
    private static final int STREAM_FETCH_SIZE = 5_000;

    private static final String INSERT_SQL = """
            INSERT INTO candle (market_id, open_time, close_time, open, high, low, close, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (market_id, open_time) DO UPDATE SET
                close_time = EXCLUDED.close_time,
                open       = EXCLUDED.open,
                high       = EXCLUDED.high,
                low        = EXCLUDED.low,
                close      = EXCLUDED.close,
                volume     = EXCLUDED.volume
            """;

    private static final String SELECT_RANGE_SQL = """
            SELECT open_time, close_time, open, high, low, close, volume
            FROM candle
            WHERE market_id = ? AND open_time >= ? AND open_time <= ?
            ORDER BY open_time
            """;

    private final JdbcTemplate jdbc;

    public CandleJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Upserts a batch.
     *
     * <p>{@code ON CONFLICT DO UPDATE} rather than {@code DO NOTHING}: re-fetching a range must be
     * able to correct a candle that was stored while it was still forming, and must stay harmless
     * when the data is identical. That makes backfill idempotent and safe to overlap.
     */
    @Transactional
    public int upsert(long marketId, List<Candle> candles) {
        if (candles.isEmpty()) {
            return 0;
        }
        int[][] results = jdbc.batchUpdate(INSERT_SQL, candles, 1_000, (ps, candle) -> {
            ps.setLong(1, marketId);
            ps.setTimestamp(2, Timestamp.from(Instant.ofEpochMilli(candle.openTime())));
            ps.setTimestamp(3, Timestamp.from(Instant.ofEpochMilli(candle.closeTime())));
            ps.setBigDecimal(4, BigDecimal.valueOf(candle.open()));
            ps.setBigDecimal(5, BigDecimal.valueOf(candle.high()));
            ps.setBigDecimal(6, BigDecimal.valueOf(candle.low()));
            ps.setBigDecimal(7, BigDecimal.valueOf(candle.close()));
            ps.setBigDecimal(8, BigDecimal.valueOf(candle.volume()));
        });
        int affected = 0;
        for (int[] batch : results) {
            for (int rows : batch) {
                affected += Math.max(rows, 0);
            }
        }
        log.debug("Upserted {} candles for market {}", candles.size(), marketId);
        return affected;
    }

    /**
     * Streams an inclusive range in chronological order using a server-side cursor, so memory stays
     * flat no matter how many candles the range contains.
     *
     * <p>Must run inside a transaction: PostgreSQL only honours the fetch size when the connection
     * is not in autocommit mode, and would otherwise materialise the whole result set.
     */
    @Transactional(readOnly = true)
    public long stream(long marketId, Instant from, Instant to, Consumer<Candle> consumer) {
        long[] count = {0};
        jdbc.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(SELECT_RANGE_SQL,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            ps.setFetchSize(STREAM_FETCH_SIZE);
            ps.setLong(1, marketId);
            ps.setTimestamp(2, Timestamp.from(from));
            ps.setTimestamp(3, Timestamp.from(to));
            return ps;
        }, (ResultSet rs) -> {
            consumer.accept(mapRow(rs));
            count[0]++;
        });
        return count[0];
    }

    /** Bounded read for the API. */
    public List<Candle> findRange(long marketId, Instant from, Instant to, int limit) {
        return jdbc.query(SELECT_RANGE_SQL + " LIMIT " + Math.max(1, limit),
                (rs, rowNum) -> mapRow(rs), marketId, Timestamp.from(from), Timestamp.from(to));
    }

    /**
     * The most recent {@code limit} stored candles, oldest first.
     *
     * <p>Distinct from asking for a wall-clock window: a chart wants the latest data that exists,
     * and would otherwise be handed an empty result whenever ingestion is behind or a market has
     * gone quiet. The query walks the primary key backwards and the page is reversed in memory.
     */
    public List<Candle> findLatest(long marketId, int limit) {
        List<Candle> descending = jdbc.query("""
                SELECT open_time, close_time, open, high, low, close, volume
                FROM candle
                WHERE market_id = ?
                ORDER BY open_time DESC
                LIMIT ?
                """, (rs, rowNum) -> mapRow(rs), marketId, Math.max(1, limit));
        java.util.Collections.reverse(descending);
        return descending;
    }

    /** Most recent stored candle open time, used to decide where backfill should resume. */
    public Optional<Instant> lastOpenTime(long marketId) {
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT max(open_time) FROM candle WHERE market_id = ?", Timestamp.class, marketId))
                .map(Timestamp::toInstant);
    }

    public Optional<Instant> firstOpenTime(long marketId) {
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT min(open_time) FROM candle WHERE market_id = ?", Timestamp.class, marketId))
                .map(Timestamp::toInstant);
    }

    public long count(long marketId) {
        Long value = jdbc.queryForObject(
                "SELECT count(*) FROM candle WHERE market_id = ?", Long.class, marketId);
        return value == null ? 0 : value;
    }

    public int deleteRange(long marketId, Instant from, Instant to) {
        return jdbc.update("DELETE FROM candle WHERE market_id = ? AND open_time >= ? AND open_time <= ?",
                marketId, Timestamp.from(from), Timestamp.from(to));
    }

    /**
     * Finds holes in a stored range by comparing each candle's open time with its successor's.
     *
     * <p>Done in SQL with a window function so a year of one-minute candles costs one pass instead
     * of half a million round trips. Gaps are expected and legitimate — exchange maintenance, a
     * delisted period, a missed websocket window — and the market-data layer heals them by
     * refetching just the affected spans.
     */
    public List<CandleGap> findGaps(long marketId, Timeframe timeframe, Instant from, Instant to) {
        long step = timeframe.durationMillis();
        String sql = """
                SELECT open_time, next_open
                FROM (
                    SELECT open_time, lead(open_time) OVER (ORDER BY open_time) AS next_open
                    FROM candle
                    WHERE market_id = ? AND open_time >= ? AND open_time <= ?
                ) windowed
                WHERE next_open IS NOT NULL
                  AND extract(epoch FROM (next_open - open_time)) * 1000 > ?
                ORDER BY open_time
                """;
        return jdbc.query(sql, (rs, rowNum) -> {
            Instant after = rs.getTimestamp("open_time").toInstant();
            Instant before = rs.getTimestamp("next_open").toInstant();
            long missing = (before.toEpochMilli() - after.toEpochMilli()) / step - 1;
            return new CandleGap(after, before, missing);
        }, marketId, Timestamp.from(from), Timestamp.from(to), step);
    }

    /** Loads a bounded range fully into memory. Only for small windows; prefer {@link #stream}. */
    public List<Candle> loadAll(long marketId, Instant from, Instant to) {
        List<Candle> candles = new ArrayList<>();
        stream(marketId, from, to, candles::add);
        return candles;
    }

    private static Candle mapRow(ResultSet rs) throws SQLException {
        return new Candle(
                rs.getTimestamp("open_time").toInstant().toEpochMilli(),
                rs.getTimestamp("close_time").toInstant().toEpochMilli(),
                rs.getBigDecimal("open").doubleValue(),
                rs.getBigDecimal("high").doubleValue(),
                rs.getBigDecimal("low").doubleValue(),
                rs.getBigDecimal("close").doubleValue(),
                rs.getBigDecimal("volume").doubleValue());
    }
}
