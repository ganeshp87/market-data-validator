package com.marketdata.validator.store;

import com.marketdata.validator.model.Alert;
import com.marketdata.validator.model.Alert.Severity;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Repository
public class AlertStore {

    private final JdbcTemplate jdbc;
    private final List<Consumer<Alert>> listeners = new CopyOnWriteArrayList<>();

    private static final RowMapper<Alert> ROW_MAPPER = (rs, rowNum) -> {
        Alert a = new Alert();
        a.setId(rs.getLong("id"));
        a.setArea(rs.getString("area"));
        a.setSeverity(Severity.valueOf(rs.getString("severity")));
        a.setMessage(rs.getString("message"));
        a.setAcknowledged(rs.getInt("acknowledged") == 1);
        a.setCreatedAt(Instant.parse(rs.getString("created_at")));
        return a;
    };

    public AlertStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Alert save(Alert alert) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO alerts (area, severity, message, acknowledged, created_at) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, alert.getArea());
            ps.setString(2, alert.getSeverity().name());
            ps.setString(3, alert.getMessage());
            ps.setInt(4, alert.isAcknowledged() ? 1 : 0);
            ps.setString(5, alert.getCreatedAt().toString());
            return ps;
        }, keyHolder);

        alert.setId(keyHolder.getKey().longValue());
        notifyListeners(alert);
        return alert;
    }

    public List<Alert> findAll() {
        return jdbc.query("SELECT * FROM alerts ORDER BY created_at DESC", ROW_MAPPER);
    }

    public Optional<Alert> findById(long id) {
        List<Alert> results = jdbc.query("SELECT * FROM alerts WHERE id = ?", ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<Alert> findUnacknowledged() {
        return jdbc.query("SELECT * FROM alerts WHERE acknowledged = 0 ORDER BY created_at DESC", ROW_MAPPER);
    }

    public List<Alert> findByArea(String area) {
        return jdbc.query("SELECT * FROM alerts WHERE area = ? ORDER BY created_at DESC", ROW_MAPPER, area);
    }

    public boolean acknowledge(long id) {
        int rows = jdbc.update("UPDATE alerts SET acknowledged = 1 WHERE id = ?", id);
        return rows > 0;
    }

    public boolean acknowledgeAll() {
        int rows = jdbc.update("UPDATE alerts SET acknowledged = 1 WHERE acknowledged = 0");
        return rows > 0;
    }

    public boolean delete(long id) {
        int rows = jdbc.update("DELETE FROM alerts WHERE id = ?", id);
        return rows > 0;
    }

    public int deleteAll() {
        return jdbc.update("DELETE FROM alerts");
    }

    public long count() {
        Long result = jdbc.queryForObject("SELECT COUNT(*) FROM alerts", Long.class);
        return result != null ? result : 0;
    }

    public long countUnacknowledged() {
        Long result = jdbc.queryForObject("SELECT COUNT(*) FROM alerts WHERE acknowledged = 0", Long.class);
        return result != null ? result : 0;
    }

    // --- Listener management for SSE broadcasting ---

    public void addListener(Consumer<Alert> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<Alert> listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(Alert alert) {
        for (Consumer<Alert> listener : listeners) {
            try {
                listener.accept(alert);
            } catch (Exception e) {
                // Don't let a bad listener break the save flow
            }
        }
    }
}
