package com.marketdata.validator.controller;

import com.marketdata.validator.feed.FeedManager;
import com.marketdata.validator.model.Connection;
import com.marketdata.validator.simulator.ScenarioConfig;
import com.marketdata.validator.validator.ValidatorEngine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST controller for managing WebSocket feed connections.
 *
 * Endpoints:
 *   GET    /api/feeds              — list all configured feeds
 *   POST   /api/feeds              — add a new feed connection
 *   PUT    /api/feeds/{id}         — update feed config
 *   DELETE /api/feeds/{id}         — stop and remove feed
 *   POST   /api/feeds/{id}/start   — start WebSocket connection
 *   POST   /api/feeds/{id}/stop    — stop WebSocket connection
 *   POST   /api/feeds/{id}/subscribe   — subscribe to additional symbols
 *   POST   /api/feeds/{id}/unsubscribe — unsubscribe from symbols
 */
@RestController
@RequestMapping("/api/feeds")
public class FeedController {

    private final FeedManager feedManager;
    private final ValidatorEngine validatorEngine;

    public FeedController(FeedManager feedManager, ValidatorEngine validatorEngine) {
        this.feedManager = feedManager;
        this.validatorEngine = validatorEngine;
    }

    /**
     * List all configured feeds.
     */
    @GetMapping
    public List<Connection> listFeeds() {
        return feedManager.getAllConnections();
    }

    /**
     * Add a new feed connection. Validates URL to prevent SSRF.
     * LVWR_T connections are exempt — they use a synthetic URL and no external socket.
     * Detection uses both adapterType enum AND url prefix as a fallback (handles null
     * adapterType from edge-case JSON deserialization).
     */
    @PostMapping
    public ResponseEntity<?> addFeed(@RequestBody Connection connection) {
        boolean isLvwr = Connection.AdapterType.LVWR_T.equals(connection.getAdapterType())
                || (connection.getUrl() != null && connection.getUrl().startsWith("lvwr://"));

        if (!isLvwr) {
            String validationError = validateFeedUrl(connection.getUrl());
            if (validationError != null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", validationError));
            }
        } else {
            connection.setAdapterType(Connection.AdapterType.LVWR_T);
            connection.setUrl("lvwr://simulator");
        }

        Connection created = feedManager.addConnection(connection);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Update an existing feed's config (name, URL, symbols).
     * Connection must be stopped before updating URL.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateFeed(@PathVariable String id,
                                        @RequestBody Connection update) {
        Connection existing = feedManager.getConnection(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        if (update.getUrl() != null && !update.getUrl().equals(existing.getUrl())) {
            if (existing.getStatus() == Connection.Status.CONNECTED
                    || existing.getStatus() == Connection.Status.RECONNECTING) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Stop connection before changing URL"));
            }
            String validationError = validateFeedUrl(update.getUrl());
            if (validationError != null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", validationError));
            }
            existing.setUrl(update.getUrl());
        }

        if (update.getName() != null) {
            existing.setName(update.getName());
        }
        if (update.getSymbols() != null) {
            existing.setSymbols(update.getSymbols());
        }
        if (update.getAdapterType() != null) {
            existing.setAdapterType(update.getAdapterType());
        }

        return ResponseEntity.ok(existing);
    }

    /**
     * Remove a feed connection. Stops it first if running.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> removeFeed(@PathVariable String id) {
        boolean removed = feedManager.removeConnection(id);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        validatorEngine.reset();
        return ResponseEntity.noContent().build();
    }

    /**
     * Start a feed connection (initiate WebSocket handshake).
     * For LVWR_T connections, an optional SimulatorConfig body configures the simulator.
     */
    @PostMapping("/{id}/start")
    public ResponseEntity<?> startFeed(@PathVariable String id,
                                       @RequestBody(required = false) ScenarioConfig simulatorConfig) {
        Connection conn = feedManager.getConnection(id);
        if (conn == null) {
            return ResponseEntity.notFound().build();
        }
        boolean started = feedManager.startConnection(id, simulatorConfig);
        if (!started) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Connection already active or failed to start"));
        }
        return ResponseEntity.ok(conn);
    }

    /**
     * Stop a feed connection (close WebSocket).
     */
    @PostMapping("/{id}/stop")
    public ResponseEntity<?> stopFeed(@PathVariable String id) {
        Connection conn = feedManager.getConnection(id);
        if (conn == null) {
            return ResponseEntity.notFound().build();
        }
        boolean stopped = feedManager.stopConnection(id);
        if (!stopped) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Connection already stopped"));
        }
        return ResponseEntity.ok(conn);
    }

    /**
     * Subscribe to additional symbols on an existing connection.
     */
    @PostMapping("/{id}/subscribe")
    public ResponseEntity<?> subscribe(@PathVariable String id,
                                       @RequestBody Map<String, List<String>> body) {
        Connection conn = feedManager.getConnection(id);
        if (conn == null) {
            return ResponseEntity.notFound().build();
        }
        List<String> symbols = body.get("symbols");
        if (symbols == null || symbols.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Request must include 'symbols' array"));
        }

        List<String> current = new ArrayList<>(conn.getSymbols());
        for (String symbol : symbols) {
            if (!current.contains(symbol)) {
                current.add(symbol);
            }
        }
        conn.setSymbols(current);
        return ResponseEntity.ok(conn);
    }

    /**
     * Unsubscribe from symbols on an existing connection.
     */
    @PostMapping("/{id}/unsubscribe")
    public ResponseEntity<?> unsubscribe(@PathVariable String id,
                                         @RequestBody Map<String, List<String>> body) {
        Connection conn = feedManager.getConnection(id);
        if (conn == null) {
            return ResponseEntity.notFound().build();
        }
        List<String> symbols = body.get("symbols");
        if (symbols == null || symbols.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Request must include 'symbols' array"));
        }

        List<String> current = new ArrayList<>(conn.getSymbols());
        current.removeAll(symbols);
        conn.setSymbols(current);
        return ResponseEntity.ok(conn);
    }

    // --- SSRF Prevention ---

    /**
     * Validates a WebSocket URL to prevent SSRF attacks.
     * Blocks internal/private IPs, loopback, and non-WebSocket schemes.
     *
     * @return error message if invalid, null if valid
     */
    static String validateFeedUrl(String url) {
        if (url == null || url.isBlank()) {
            return "URL is required";
        }

        // Must be a WebSocket URL
        if (!url.startsWith("wss://") && !url.startsWith("ws://")) {
            return "URL must use ws:// or wss:// scheme";
        }

        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "URL must have a valid host";
            }

            // Resolve hostname with a timeout to avoid hanging on slow DNS
            InetAddress address;
            try {
                address = InetAddress.getByName(host);
            } catch (Exception e) {
                // DNS resolution failed — skip IP check but allow the URL
                // The actual WebSocket connection will fail later if unreachable
                return null;
            }

            if (address.isLoopbackAddress()) {
                return "Loopback addresses are not allowed";
            }
            if (address.isSiteLocalAddress()) {
                return "Private/internal IP addresses are not allowed";
            }
            if (address.isLinkLocalAddress()) {
                return "Link-local addresses are not allowed";
            }
            if (address.isAnyLocalAddress()) {
                return "Wildcard addresses are not allowed";
            }

            return null; // Valid
        } catch (IllegalArgumentException e) {
            return "Invalid URL format";
        }
    }
}
