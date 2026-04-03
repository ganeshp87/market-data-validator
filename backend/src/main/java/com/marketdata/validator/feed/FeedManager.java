package com.marketdata.validator.feed;

import com.marketdata.validator.model.Connection;
import com.marketdata.validator.model.Tick;
import com.marketdata.validator.simulator.LVWRChaosSimulator;
import com.marketdata.validator.simulator.ScenarioConfig;
import com.marketdata.validator.store.ConnectionStore;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Manages all WebSocket feed connections.
 * Provides CRUD for connections, start/stop, tick broadcasting,
 * and health monitoring (stale feed detection).
 */
@Component
public class FeedManager {

    private static final Logger log = LoggerFactory.getLogger(FeedManager.class);
    private static final Duration STALE_THRESHOLD = Duration.ofSeconds(30);

    private final Map<String, FeedConnection> connections = new ConcurrentHashMap<>();
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
                FeedAdapter adapter = createAdapter(conn.getAdapterType());
                FeedConnection feedConn = new FeedConnection(conn, adapter);
                feedConn.addTickListener(this::broadcastTick);
                connections.put(conn.getId(), feedConn);
                log.info("Loaded saved connection: {} ({})", conn.getName(), conn.getId());
                feedConn.connect();
                log.info("Auto-connected: {}", conn.getName());
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
        FeedAdapter adapter = createAdapter(connection.getAdapterType());
        FeedConnection feedConn = new FeedConnection(connection, adapter);
        feedConn.addTickListener(this::broadcastTick);
        connections.put(connection.getId(), feedConn);
        connectionStore.save(connection);
        log.info("Added connection: {} ({})", connection.getName(), connection.getId());
        return connection;
    }

    /**
     * Remove a connection. Disconnects first if connected.
     */
    public boolean removeConnection(String connectionId) {
        FeedConnection feedConn = connections.remove(connectionId);
        if (feedConn == null) {
            return false;
        }
        if (feedConn.isConnected()) {
            feedConn.disconnect();
        }
        connectionStore.delete(connectionId);
        log.info("Removed connection: {}", connectionId);
        return true;
    }

    /**
     * Start a connection's WebSocket feed.
     * For LVWR_T connections, launches LVWRChaosSimulator on a virtual thread.
     */
    public boolean startConnection(String connectionId) {
        FeedConnection feedConn = connections.get(connectionId);
        if (feedConn == null) {
            return false;
        }
        Connection conn = feedConn.getConnection();
        if (conn.getAdapterType() == Connection.AdapterType.LVWR_T) {
            // Reset all validators before starting a fresh simulation run
            validatorEngine.reset();
            ScenarioConfig config = new ScenarioConfig();
            LVWRChaosSimulator sim = new LVWRChaosSimulator(connectionId, config, this::broadcastTick);
            simulators.put(connectionId, sim);
            Thread.ofVirtual().name("lvwr-simulator-" + connectionId).start(sim);
            conn.setStatus(Connection.Status.CONNECTED);
            log.info("Started LVWR_T simulator for connection: {}", connectionId);
        } else {
            feedConn.connect();
        }
        return true;
    }

    /**
     * Stop a connection's WebSocket feed.
     */
    public boolean stopConnection(String connectionId) {
        FeedConnection feedConn = connections.get(connectionId);
        if (feedConn == null) {
            return false;
        }
        Connection conn = feedConn.getConnection();
        if (conn.getAdapterType() == Connection.AdapterType.LVWR_T) {
            LVWRChaosSimulator sim = simulators.remove(connectionId);
            if (sim != null) sim.stop();
            conn.setStatus(Connection.Status.DISCONNECTED);
            log.info("Stopped LVWR_T simulator for connection: {}", connectionId);
        } else {
            feedConn.disconnect();
        }
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
     * Get a connection by ID.
     */
    public Connection getConnection(String connectionId) {
        FeedConnection feedConn = connections.get(connectionId);
        return feedConn != null ? feedConn.getConnection() : null;
    }

    /**
     * Get all connections.
     */
    public List<Connection> getAllConnections() {
        return connections.values().stream()
                .map(FeedConnection::getConnection)
                .toList();
    }

    /**
     * Register a global tick listener — receives ticks from ALL connections.
     * Used by ValidatorEngine, SessionRecorder, SSE StreamController.
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
     * Returns list of stale connection IDs.
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
     * Get total number of managed connections.
     */
    public int getConnectionCount() {
        return connections.size();
    }

    /**
     * Get count of currently connected feeds.
     */
    public long getActiveConnectionCount() {
        return connections.values().stream()
                .filter(FeedConnection::isConnected)
                .count();
    }

    // --- Lifecycle ---

    @PreDestroy
    void destroy() {
        log.info("FeedManager shutting down — disconnecting {} connection(s)", connections.size());
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
        for (Consumer<Tick> listener : globalTickListeners) {
            try {
                listener.accept(tick);
            } catch (Exception e) {
                log.error("Global tick listener error: {}", e.getMessage());
            }
        }
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
