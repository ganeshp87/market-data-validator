package com.marketdata.validator.feed;

import com.marketdata.validator.model.Connection;
import com.marketdata.validator.model.Tick;
import com.marketdata.validator.simulator.LVWRChaosSimulator;
import com.marketdata.validator.simulator.ScenarioConfig;
import com.marketdata.validator.store.ConnectionStore;
import com.marketdata.validator.validator.ReconnectionValidator;
import com.marketdata.validator.validator.ValidatorEngine;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Manages all WebSocket feed connections.
 * Provides CRUD for connections, start/stop, tick broadcasting,
 * and health monitoring (stale feed detection).
 *
 * LVWR_T (simulator) connections are handled separately from real WebSocket
 * connections — they never go through FeedConnection.
 */
@Component
public class FeedManager {

    private static final Logger log = LoggerFactory.getLogger(FeedManager.class);
    private static final Duration STALE_THRESHOLD = Duration.ofSeconds(30);

    /** Real WebSocket connections */
    private final Map<String, FeedConnection> connections = new ConcurrentHashMap<>();
    /** LVWR_T simulator connections — Connection object only, no FeedConnection */
    private final Map<String, Connection> simulatorConnections = new ConcurrentHashMap<>();
    /** Running simulator Runnables keyed by connectionId */
    private final Map<String, LVWRChaosSimulator> simulators = new ConcurrentHashMap<>();

    private final List<Consumer<Tick>> globalTickListeners = new CopyOnWriteArrayList<>();
    private final ConnectionStore connectionStore;
    private final ValidatorEngine validatorEngine;

    public FeedManager(ConnectionStore connectionStore, @Lazy ValidatorEngine validatorEngine) {
        this.connectionStore = connectionStore;
        this.validatorEngine = validatorEngine;
    }

    /**
     * Load saved connections from the database and auto-connect them.
     * Uses CommandLineRunner to ensure DB schema is initialized first.
     */
    @Bean
    @Order(2)
    CommandLineRunner loadSavedConnections() {
        return args -> {
            List<Connection> saved = connectionStore.findAll();
            for (Connection conn : saved) {
                if (conn.getAdapterType() == Connection.AdapterType.LVWR_T) {
                    simulatorConnections.put(conn.getId(), conn);
                    log.info("Loaded saved LVWR_T connection: {} ({})", conn.getName(), conn.getId());
                    startConnection(conn.getId(), new ScenarioConfig());
                } else {
                    FeedAdapter adapter = createAdapter(conn.getAdapterType());
                    FeedConnection feedConn = new FeedConnection(conn, adapter);
                    feedConn.addTickListener(this::broadcastTick);
                    wireReconnectionCallbacks(feedConn, conn.getId());
                    connections.put(conn.getId(), feedConn);
                    log.info("Loaded saved connection: {} ({})", conn.getName(), conn.getId());
                    feedConn.connect();
                    log.info("Auto-connected: {}", conn.getName());
                }
            }
            if (!saved.isEmpty()) {
                log.info("Restored {} saved connection(s)", saved.size());
            }
        };
    }

    /**
     * Add a new connection (does not start it).
     */
    public Connection addConnection(Connection connection) {
        if (connection.getAdapterType() == Connection.AdapterType.LVWR_T) {
            if (connection.getUrl() == null || connection.getUrl().isBlank()) {
                connection.setUrl("lvwr://simulator");
            }
            simulatorConnections.put(connection.getId(), connection);
            connectionStore.save(connection);
            log.info("Added LVWR_T connection: {} ({})", connection.getName(), connection.getId());
            return connection;
        }
        FeedAdapter adapter = createAdapter(connection.getAdapterType());
        FeedConnection feedConn = new FeedConnection(connection, adapter);
        feedConn.addTickListener(this::broadcastTick);
        wireReconnectionCallbacks(feedConn, connection.getId());
        connections.put(connection.getId(), feedConn);
        connectionStore.save(connection);
        log.info("Added connection: {} ({})", connection.getName(), connection.getId());
        return connection;
    }

    /**
     * Remove a connection. Disconnects first if connected.
     */
    public boolean removeConnection(String connectionId) {
        // Check simulator connections first
        Connection simConn = simulatorConnections.remove(connectionId);
        if (simConn != null) {
            LVWRChaosSimulator sim = simulators.remove(connectionId);
            if (sim != null) sim.stop();
            connectionStore.delete(connectionId);
            // Avoid logging user-controlled data directly
            log.info("Removed LVWR_T connection");
            return true;
        }
        FeedConnection feedConn = connections.remove(connectionId);
        if (feedConn == null) {
            return false;
        }
        if (feedConn.isConnected()) {
            feedConn.disconnect();
        }
        connectionStore.delete(connectionId);
        // Avoid logging user-controlled data directly
        log.info("Removed connection");
        return true;
    }

    /**
     * Start a connection's WebSocket feed (no simulator config).
     */
    public boolean startConnection(String connectionId) {
        return startConnection(connectionId, null);
    }

    /**
     * Start a connection. For LVWR_T, launches LVWRChaosSimulator on a virtual thread.
     * @param config optional SimulatorConfig; defaults are used if null
     */
    public boolean startConnection(String connectionId, ScenarioConfig config) {
        // LVWR_T simulator path
        Connection simConn = simulatorConnections.get(connectionId);
        if (simConn != null) {
            ScenarioConfig runConfig = config != null ? config : new ScenarioConfig();
            validatorEngine.reset();
            // Stop any previously running simulator and wait for it to fully exit
            // before starting a new one to prevent two simulators emitting to the same feed.
            LVWRChaosSimulator existing = simulators.remove(connectionId);
            if (existing != null) {
                existing.stop();
                try {
                    if (!existing.waitForStop(10_000)) {
                        // Avoid logging user-controlled data directly
                        log.warn("Old simulator did not stop within 10s  proceeding anyway");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            LVWRChaosSimulator sim = new LVWRChaosSimulator(connectionId, runConfig, this::broadcastTick);
            simulators.put(connectionId, sim);
            Thread.ofVirtual().name("lvwr-simulator-" + connectionId).start(sim);
            simConn.setStatus(Connection.Status.CONNECTED);
            simConn.setConnectedAt(Instant.now());
            // Avoid logging user-controlled data directly
            log.info("Started LVWR_T simulator for connection");
            return true;
        }

        // Regular WebSocket path
        FeedConnection feedConn = connections.get(connectionId);
        if (feedConn == null) {
            return false;
        }
        feedConn.connect();
        return true;
    }

    /**
     * Stop a connection's WebSocket feed.
     */
    public boolean stopConnection(String connectionId) {
        // LVWR_T simulator path
        Connection simConn = simulatorConnections.get(connectionId);
        if (simConn != null) {
            LVWRChaosSimulator sim = simulators.remove(connectionId);
            if (sim != null) sim.stop();
            simConn.setStatus(Connection.Status.DISCONNECTED);
            // Avoid logging user-controlled data directly
            log.info("Stopped LVWR_T simulator for connection");
            return true;
        }

        FeedConnection feedConn = connections.get(connectionId);
        if (feedConn == null) {
            return false;
        }
        feedConn.disconnect();
        return true;
    }

    /**
     * Get the running simulator for the given connection, if any.
     */
    public LVWRChaosSimulator getSimulator(String connectionId) {
        return simulators.get(connectionId);
    }

    /**
     * Get all running simulators.
     */
    public Map<String, LVWRChaosSimulator> getAllSimulators() {
        return Map.copyOf(simulators);
    }

    /**
     * Get a connection by ID (checks both LVWR_T and regular connections).
     */
    public Connection getConnection(String connectionId) {
        Connection simConn = simulatorConnections.get(connectionId);
        if (simConn != null) return simConn;
        FeedConnection feedConn = connections.get(connectionId);
        return feedConn != null ? feedConn.getConnection() : null;
    }

    /**
     * Get all connections (regular + LVWR_T simulator connections).
     */
    public List<Connection> getAllConnections() {
        List<Connection> all = new ArrayList<>();
        connections.values().stream().map(FeedConnection::getConnection).forEach(all::add);
        all.addAll(simulatorConnections.values());
        return all;
    }

    /**
     * Register a global tick listener — receives ticks from ALL connections.
     */
    public void addGlobalTickListener(Consumer<Tick> listener) {
        globalTickListeners.add(listener);
    }

    /**
     * Remove a global tick listener.
     */
    public void removeGlobalTickListener(Consumer<Tick> listener) {
        globalTickListeners.remove(listener);
    }

    /**
     * Check all connections for staleness (no ticks within threshold).
     */
    public List<String> checkHealth() {
        Instant cutoff = Instant.now().minus(STALE_THRESHOLD);
        return connections.values().stream()
                .filter(fc -> fc.isConnected())
                .filter(fc -> {
                    Instant lastTick = fc.getConnection().getLastTickAt();
                    return lastTick != null && lastTick.isBefore(cutoff);
                })
                .map(fc -> fc.getConnection().getId())
                .toList();
    }

    /**
     * Get total number of managed connections (regular + simulator).
     */
    public int getConnectionCount() {
        return connections.size() + simulatorConnections.size();
    }

    /**
     * Get count of currently connected feeds (regular + running simulators).
     */
    public long getActiveConnectionCount() {
        long regularActive = connections.values().stream()
                .filter(FeedConnection::isConnected)
                .count();
        long simActive = simulatorConnections.values().stream()
                .filter(c -> c.getStatus() == Connection.Status.CONNECTED)
                .count();
        return regularActive + simActive;
    }

    // --- Lifecycle ---

    @PreDestroy
    void destroy() {
        log.info("FeedManager shutting down — disconnecting {} connection(s)", connections.size());
        simulators.values().forEach(sim -> { try { sim.stop(); } catch (Exception ignored) {} });
        connections.values().forEach(fc -> {
            try {
                fc.disconnect();
            } catch (Exception e) {
                log.error("Failed to disconnect feed {}: {}",
                        fc.getConnection().getName(), e.getMessage());
            }
        });
    }

    // --- Internal ---

    private void broadcastTick(Tick tick) {
        // Update tickCount/lastTickAt on the LVWR simulator connection (regular
        // FeedConnection tracks these internally; LVWR bypasses FeedConnection).
        if (tick.getFeedId() != null) {
            Connection simConn = simulatorConnections.get(tick.getFeedId());
            if (simConn != null) {
                simConn.recordTick();
            }
        }

        for (Consumer<Tick> listener : globalTickListeners) {
            try {
                listener.accept(tick);
            } catch (Exception e) {
                log.error("Global tick listener error: {}", e.getMessage());
            }
        }
    }

    private void wireReconnectionCallbacks(FeedConnection feedConn, String connectionId) {
        ReconnectionValidator rv = validatorEngine.getReconnectionValidator();
        if (rv == null) return;
        feedConn.setDisconnectCallback(() -> rv.onDisconnect(connectionId));
        feedConn.setReconnectCallback(duration -> rv.onReconnect(connectionId, duration));
        feedConn.setReconnectFailedCallback(() -> rv.onReconnectFailed(connectionId));
    }

    FeedAdapter createAdapter(Connection.AdapterType type) {
        return switch (type) {
            case BINANCE -> new BinanceAdapter();
            case FINNHUB -> new FinnhubAdapter();
            case GENERIC -> new GenericAdapter();
            case LVWR_T -> new com.marketdata.validator.simulator.LVWRSimulatorAdapter();
        };
    }
}
