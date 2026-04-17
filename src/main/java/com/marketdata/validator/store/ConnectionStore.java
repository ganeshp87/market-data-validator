package com.marketdata.validator.store;

import com.marketdata.validator.model.Connection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JDBC data access for the `connections` table.
 * Persists feed connection configurations so they survive restarts.
 */
@Repository
public class ConnectionStore {

    private final JdbcTemplate jdbc;

    private static final String INSERT_SQL =
            "INSERT INTO connections (id, name, url, adapter_type, symbols, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE connections SET name = ?, url = ?, adapter_type = ?, symbols = ?, updated_at = ? " +
            "WHERE id = ?";

    private static final String DELETE_SQL = "DELETE FROM connections WHERE id = ?";

    private static final String SELECT_ALL_SQL = "SELECT * FROM connections ORDER BY created_at";

    private static final RowMapper<Connection> ROW_MAPPER = (rs, rowNum) -> {
        Connection conn = new Connection();
        conn.setId(rs.getString("id"));
        conn.setName(rs.getString("name"));
        conn.setUrl(rs.getString("url"));
        conn.setAdapterType(Connection.AdapterType.valueOf(rs.getString("adapter_type")));
        // Symbols stored as JSON array string: ["BTCUSDT","ETHUSDT"]
        String symbolsJson = rs.getString("symbols");
        List<String> symbols = parseSymbols(symbolsJson);
        conn.setSymbols(symbols);
        return conn;
    };

    public ConnectionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Connection connection) {
        String now = Instant.now().toString();
        String symbolsJson = toSymbolsJson(connection.getSymbols());
        jdbc.update(INSERT_SQL,
                connection.getId(),
                connection.getName(),
                connection.getUrl(),
                connection.getAdapterType().name(),
                symbolsJson,
                now,
                now);
    }

    public void update(Connection connection) {
        String now = Instant.now().toString();
        String symbolsJson = toSymbolsJson(connection.getSymbols());
        jdbc.update(UPDATE_SQL,
                connection.getName(),
                connection.getUrl(),
                connection.getAdapterType().name(),
                symbolsJson,
                now,
                connection.getId());
    }

    public void delete(String id) {
        jdbc.update(DELETE_SQL, id);
    }

    public List<Connection> findAll() {
        return jdbc.query(SELECT_ALL_SQL, ROW_MAPPER);
    }

    // --- Helpers ---

    private static String toSymbolsJson(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return "[]";
        return "[" + symbols.stream()
                .map(s -> "\"" + s.replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",")) + "]";
    }

    private static List<String> parseSymbols(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) {
            return List.of();
        }
        // Simple parse: strip brackets and quotes, split by comma
        String stripped = json.replaceAll("[\\[\\]\"]", "");
        return Arrays.stream(stripped.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
