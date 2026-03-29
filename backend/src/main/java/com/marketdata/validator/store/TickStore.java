package com.marketdata.validator.store;

import com.marketdata.validator.model.Tick;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * JDBC data access for the `ticks` table.
 * Uses JdbcTemplate — no JPA, no ORM overhead.
 *
 * Prices stored as TEXT (BigDecimal.toPlainString()) for precision.
 * Timestamps stored as ISO-8601 TEXT (Instant.toString()).
 *
 * Key operations:
 *   - save: single tick insert
 *   - saveBatch: batch insert (100 ticks at a time for performance)
 *   - findBySessionId: replay a recorded session
 *   - findBySymbol: analysis queries
 */
@Repository
public class TickStore {

    private final JdbcTemplate jdbc;

    private static final String INSERT_SQL =
            "INSERT INTO ticks (session_id, feed_id, symbol, price, bid, ask, volume, " +
            "sequence_num, exchange_ts, received_ts) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final RowMapper<Tick> ROW_MAPPER = (rs, rowNum) -> {
        Tick tick = new Tick();
        tick.setId(rs.getLong("id"));
        tick.setSessionId(rs.getObject("session_id") != null ? rs.getLong("session_id") : null);
        tick.setFeedId(rs.getString("feed_id"));
        tick.setSymbol(rs.getString("symbol"));
        tick.setPrice(new BigDecimal(rs.getString("price")));
        tick.setBid(rs.getString("bid") != null ? new BigDecimal(rs.getString("bid")) : null);
        tick.setAsk(rs.getString("ask") != null ? new BigDecimal(rs.getString("ask")) : null);
        tick.setVolume(rs.getString("volume") != null ? new BigDecimal(rs.getString("volume")) : null);
        tick.setSequenceNum(rs.getLong("sequence_num"));
        tick.setExchangeTimestamp(Instant.parse(rs.getString("exchange_ts")));
        tick.setReceivedTimestamp(Instant.parse(rs.getString("received_ts")));
        return tick;
    };

    public TickStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert a single tick.
     */
    public void save(Tick tick) {
        jdbc.update(INSERT_SQL,
                tick.getSessionId(),
                tick.getFeedId(),
                tick.getSymbol(),
                tick.getPrice().toPlainString(),
                tick.getBid() != null ? tick.getBid().toPlainString() : null,
                tick.getAsk() != null ? tick.getAsk().toPlainString() : null,
                tick.getVolume() != null ? tick.getVolume().toPlainString() : null,
                tick.getSequenceNum(),
                tick.getExchangeTimestamp().toString(),
                tick.getReceivedTimestamp().toString());
    }

    /**
     * Batch insert ticks — used by SessionRecorder to flush 100 ticks at a time.
     * Much faster than individual inserts (1 round-trip vs 100).
     * Transactional: all-or-nothing — prevents partial commit on failure.
     */
    @Transactional
    public void saveBatch(List<Tick> ticks) {
        jdbc.batchUpdate(INSERT_SQL, ticks, ticks.size(), (ps, tick) -> {
            ps.setObject(1, tick.getSessionId());
            ps.setString(2, tick.getFeedId());
            ps.setString(3, tick.getSymbol());
            ps.setString(4, tick.getPrice().toPlainString());
            ps.setString(5, tick.getBid() != null ? tick.getBid().toPlainString() : null);
            ps.setString(6, tick.getAsk() != null ? tick.getAsk().toPlainString() : null);
            ps.setString(7, tick.getVolume() != null ? tick.getVolume().toPlainString() : null);
            ps.setLong(8, tick.getSequenceNum());
            ps.setString(9, tick.getExchangeTimestamp().toString());
            ps.setString(10, tick.getReceivedTimestamp().toString());
        });
    }

    /**
     * Get all ticks for a recorded session, ordered by exchange timestamp.
     */
    public List<Tick> findBySessionId(long sessionId) {
        return jdbc.query(
                "SELECT * FROM ticks WHERE session_id = ? ORDER BY exchange_ts",
                ROW_MAPPER, sessionId);
    }

    /**
     * Get ticks for a symbol within a time range.
     */
    public List<Tick> findBySymbol(String symbol, Instant from, Instant to) {
        return jdbc.query(
                "SELECT * FROM ticks WHERE symbol = ? AND exchange_ts >= ? AND exchange_ts <= ? ORDER BY exchange_ts",
                ROW_MAPPER, symbol, from.toString(), to.toString());
    }

    /**
     * Get tick count for a session.
     */
    public long countBySessionId(long sessionId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticks WHERE session_id = ?",
                Long.class, sessionId);
        return count != null ? count : 0;
    }

    /**
     * Delete all ticks for a session (when deleting a session).
     */
    public void deleteBySessionId(long sessionId) {
        jdbc.update("DELETE FROM ticks WHERE session_id = ?", sessionId);
    }
}
