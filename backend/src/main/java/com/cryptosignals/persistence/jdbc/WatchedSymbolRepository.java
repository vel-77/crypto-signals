package com.cryptosignals.persistence.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** The coins the watch list chose by volume, as of its last successful refresh. */
@Repository
public class WatchedSymbolRepository {

    /**
     * @param dailyVolume average traded value per day, in the quote asset
     * @param selectedAt  when the coin joined the list
     * @param updatedAt   when its volume was last checked
     */
    public record WatchedSymbol(String symbol, double dailyVolume, Instant selectedAt, Instant updatedAt) {
    }

    private final JdbcTemplate jdbc;

    public WatchedSymbolRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Highest volume first. */
    public List<WatchedSymbol> findAll(String exchange) {
        return jdbc.query("""
                        SELECT symbol, daily_volume, selected_at, updated_at
                        FROM watched_symbol
                        WHERE exchange = ?
                        ORDER BY daily_volume DESC, symbol
                        """,
                (rs, i) -> new WatchedSymbol(rs.getString("symbol"),
                        rs.getBigDecimal("daily_volume").doubleValue(),
                        rs.getTimestamp("selected_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                exchange);
    }

    /** Makes the stored list exactly {@code symbols}, in one transaction. */
    @Transactional
    public void replaceAll(String exchange, List<WatchedSymbol> symbols) {
        jdbc.update("DELETE FROM watched_symbol WHERE exchange = ?", exchange);
        jdbc.batchUpdate("""
                        INSERT INTO watched_symbol (exchange, symbol, daily_volume, selected_at, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                symbols, 500, (ps, s) -> {
                    ps.setString(1, exchange);
                    ps.setString(2, s.symbol());
                    ps.setBigDecimal(3, BigDecimal.valueOf(s.dailyVolume()));
                    ps.setTimestamp(4, Timestamp.from(s.selectedAt()));
                    ps.setTimestamp(5, Timestamp.from(s.updatedAt()));
                });
    }
}
