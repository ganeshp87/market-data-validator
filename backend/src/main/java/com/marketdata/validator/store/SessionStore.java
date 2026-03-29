package com.marketdata.validator.store;

import com.marketdata.validator.model.Session;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC data access for the `sessions` table.
 *
 * Sessions track recording metadata:
 *   - Start recording → creates session with status RECORDING
 *   - Stop recording → updates status to COMPLETED, sets endedAt and stats
 *   - Failed recording → status = FAILED
 *
 * Uses KeyHolder to retrieve auto-generated session ID after insert.
 */
@Repository
public class SessionStore {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Session> ROW_MAPPER = (rs, rowNum) -> {
        Session session = new Session();
        session.setId(rs.getLong("id"));
        session.setName(rs.getString("name"));
        session.setFeedId(rs.getString("feed_id"));
        session.setStatus(Session.Status.valueOf(rs.getString("status")));
        session.setStartedAt(Instant.parse(rs.getString("started_at")));
        session.setEndedAt(rs.getString("ended_at") != null ?
                Instant.parse(rs.getString("ended_at")) : null);
        session.setTickCount(rs.getLong("tick_count"));
        session.setByteSize(rs.getLong("byte_size"));
        return session;
    };

    public SessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Create a new session. Returns the session with its auto-generated ID set.
     */
    public Session create(Session session) {
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO sessions (name, feed_id, status, started_at, tick_count, byte_size) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, session.getName());
            ps.setString(2, session.getFeedId());
            ps.setString(3, session.getStatus().name());
            ps.setString(4, session.getStartedAt().toString());
            ps.setLong(5, session.getTickCount());
            ps.setLong(6, session.getByteSize());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key != null) {
            session.setId(key.longValue());
        }
        return session;
    }

    /**
     * Find a session by ID.
     */
    public Optional<Session> findById(long id) {
        List<Session> results = jdbc.query(
                "SELECT * FROM sessions WHERE id = ?", ROW_MAPPER, id);
        return results.stream().findFirst();
    }

    /**
     * List all sessions, newest first.
     */
    public List<Session> findAll() {
        return jdbc.query(
                "SELECT * FROM sessions ORDER BY started_at DESC", ROW_MAPPER);
    }

    /**
     * Update session status (e.g., RECORDING → COMPLETED).
     */
    public void updateStatus(long id, Session.Status status) {
        jdbc.update("UPDATE sessions SET status = ? WHERE id = ?",
                status.name(), id);
    }

    /**
     * Finalize a session — set endedAt, tick count, byte size, and status to COMPLETED.
     */
    public void complete(long id, long tickCount, long byteSize) {
        jdbc.update(
                "UPDATE sessions SET status = ?, ended_at = ?, tick_count = ?, byte_size = ? WHERE id = ?",
                Session.Status.COMPLETED.name(), Instant.now().toString(),
                tickCount, byteSize, id);
    }

    /**
     * Delete a session by ID.
     */
    public void delete(long id) {
        jdbc.update("DELETE FROM sessions WHERE id = ?", id);
    }

    /**
     * Count total sessions.
     */
    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM sessions", Long.class);
        return count != null ? count : 0;
    }
}
