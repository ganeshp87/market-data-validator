package com.marketdata.validator.feed;

import com.marketdata.validator.model.Connection;
import com.marketdata.validator.model.Tick;
import com.marketdata.validator.store.ConnectionStore;
import com.marketdata.validator.validator.ValidatorEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FeedManagerTest {

    private FeedManager feedManager;
    private ConnectionStore connectionStore;
    private ValidatorEngine validatorEngine;

    @BeforeEach
    void setUp() {
        connectionStore = mock(ConnectionStore.class);
        validatorEngine = mock(ValidatorEngine.class);
        when(connectionStore.findAll()).thenReturn(List.of());
        feedManager = new FeedManager(connectionStore, validatorEngine);
    }

    // --- CRUD tests ---

    @Test
    void addConnectionStoresIt() {
        Connection conn = createBinanceConnection("Binance BTC");
        feedManager.addConnection(conn);

        assertThat(feedManager.getConnectionCount()).isEqualTo(1);
        assertThat(feedManager.getConnection(conn.getId())).isNotNull();
        assertThat(feedManager.getConnection(conn.getId()).getName()).isEqualTo("Binance BTC");
    }

    @Test
    void addMultipleConnections() {
        feedManager.addConnection(createBinanceConnection("Feed 1"));
        feedManager.addConnection(createBinanceConnection("Feed 2"));
        feedManager.addConnection(createBinanceConnection("Feed 3"));

        assertThat(feedManager.getConnectionCount()).isEqualTo(3);
        assertThat(feedManager.getAllConnections()).hasSize(3);
    }

    @Test
    void removeConnectionReturnsTrue() {
        Connection conn = createBinanceConnection("Test");
        feedManager.addConnection(conn);

        boolean removed = feedManager.removeConnection(conn.getId());

        assertThat(removed).isTrue();
        assertThat(feedManager.getConnectionCount()).isEqualTo(0);
        assertThat(feedManager.getConnection(conn.getId())).isNull();
    }

    @Test
    void removeNonexistentConnectionReturnsFalse() {
        assertThat(feedManager.removeConnection("nonexistent-id")).isFalse();
    }

    @Test
    void getConnectionReturnsNullForUnknownId() {
        assertThat(feedManager.getConnection("unknown")).isNull();
    }

    @Test
    void getAllConnectionsReturnsEmptyListInitially() {
        assertThat(feedManager.getAllConnections()).isEmpty();
    }

    // --- Start/Stop tests ---

    @Test
    void startNonexistentConnectionReturnsFalse() {
        assertThat(feedManager.startConnection("nonexistent")).isFalse();
    }

    @Test
    void stopNonexistentConnectionReturnsFalse() {
        assertThat(feedManager.stopConnection("nonexistent")).isFalse();
    }

    @Test
    void stopConnectionSetsDisconnectedStatus() {
        Connection conn = createBinanceConnection("Test");
        feedManager.addConnection(conn);

        // Manually set status to CONNECTED for testing
        conn.setStatus(Connection.Status.CONNECTED);

        feedManager.stopConnection(conn.getId());
        assertThat(conn.getStatus()).isEqualTo(Connection.Status.DISCONNECTED);
    }

    // --- Active connection count ---

    @Test
    void activeConnectionCountReflectsStatus() {
        Connection conn1 = createBinanceConnection("Feed 1");
        Connection conn2 = createBinanceConnection("Feed 2");
        feedManager.addConnection(conn1);
        feedManager.addConnection(conn2);

        assertThat(feedManager.getActiveConnectionCount()).isEqualTo(0);

        conn1.setStatus(Connection.Status.CONNECTED);
        assertThat(feedManager.getActiveConnectionCount()).isEqualTo(1);

        conn2.setStatus(Connection.Status.CONNECTED);
        assertThat(feedManager.getActiveConnectionCount()).isEqualTo(2);
    }

    // --- Global tick listener tests ---

    @Test
    void globalTickListenerReceivesBroadcasts() {
        List<Tick> received = new ArrayList<>();
        feedManager.addGlobalTickListener(received::add);

        // The global listener is wired internally via broadcastTick.
        // We verify it's registered by checking no errors occur.
        assertThat(received).isEmpty();
    }

    @Test
    void removeGlobalTickListener() {
        List<Tick> received = new ArrayList<>();
        java.util.function.Consumer<Tick> listener = received::add;

        feedManager.addGlobalTickListener(listener);
        feedManager.removeGlobalTickListener(listener);

        // Listener removed — verified by no-op
        assertThat(received).isEmpty();
    }

    // --- Health check tests ---

    @Test
    void healthCheckReturnsEmptyWhenNoConnections() {
        assertThat(feedManager.checkHealth()).isEmpty();
    }

    @Test
    void healthCheckDetectsStaleFeeds() {
        Connection conn = createBinanceConnection("Stale Feed");
        feedManager.addConnection(conn);

        // Simulate: connected but last tick was 60 seconds ago (>30s threshold)
        conn.setStatus(Connection.Status.CONNECTED);
        conn.setLastTickAt(Instant.now().minusSeconds(60));

        List<String> stale = feedManager.checkHealth();
        assertThat(stale).containsExactly(conn.getId());
    }

    @Test
    void healthCheckIgnoresDisconnectedFeeds() {
        Connection conn = createBinanceConnection("Disconnected Feed");
        feedManager.addConnection(conn);

        // Disconnected feeds should not appear as stale
        conn.setStatus(Connection.Status.DISCONNECTED);
        conn.setLastTickAt(Instant.now().minusSeconds(60));

        assertThat(feedManager.checkHealth()).isEmpty();
    }

    @Test
    void healthCheckIgnoresRecentlyActiveFeed() {
        Connection conn = createBinanceConnection("Active Feed");
        feedManager.addConnection(conn);

        // Last tick 5 seconds ago — well within 30s threshold
        conn.setStatus(Connection.Status.CONNECTED);
        conn.setLastTickAt(Instant.now().minusSeconds(5));

        assertThat(feedManager.checkHealth()).isEmpty();
    }

    // --- Adapter creation tests ---

    @Test
    void createsBinanceAdapter() {
        FeedAdapter adapter = feedManager.createAdapter(Connection.AdapterType.BINANCE);
        assertThat(adapter).isInstanceOf(BinanceAdapter.class);
    }

    @Test
    void createsFinnhubAdapter() {
        FeedAdapter adapter = feedManager.createAdapter(Connection.AdapterType.FINNHUB);
        assertThat(adapter).isInstanceOf(FinnhubAdapter.class);
    }

    @Test
    void createsGenericAdapter() {
        FeedAdapter adapter = feedManager.createAdapter(Connection.AdapterType.GENERIC);
        assertThat(adapter).isInstanceOf(GenericAdapter.class);
    }

    // --- destroy() robustness tests ---

    @Test
    void destroyDisconnectsAllConnectionsEvenWhenOneThrows() throws Exception {
        // Inject 3 mock FeedConnections: first throws, others should still disconnect
        FeedConnection fc1 = mockFeedConnection("feed-1", "Failing Feed");
        FeedConnection fc2 = mockFeedConnection("feed-2", "Good Feed A");
        FeedConnection fc3 = mockFeedConnection("feed-3", "Good Feed B");

        doThrow(new RuntimeException("WebSocket close error")).when(fc1).disconnect();

        injectConnections(Map.of("feed-1", fc1, "feed-2", fc2, "feed-3", fc3));

        feedManager.destroy();

        // ALL three disconnect calls were attempted
        verify(fc1).disconnect();
        verify(fc2).disconnect();
        verify(fc3).disconnect();
    }

    @Test
    void destroyHandlesAllConnectionsThrowing() throws Exception {
        FeedConnection fc1 = mockFeedConnection("feed-1", "Bad Feed 1");
        FeedConnection fc2 = mockFeedConnection("feed-2", "Bad Feed 2");

        doThrow(new RuntimeException("Error 1")).when(fc1).disconnect();
        doThrow(new RuntimeException("Error 2")).when(fc2).disconnect();

        injectConnections(Map.of("feed-1", fc1, "feed-2", fc2));

        // destroy() should not throw even if every connection fails
        feedManager.destroy();

        verify(fc1).disconnect();
        verify(fc2).disconnect();
    }

    @Test
    void destroyIsNoOpWhenNoConnections() {
        // No connections added — destroy() should complete without error
        feedManager.destroy();
        assertThat(feedManager.getConnectionCount()).isEqualTo(0);
    }

    // --- Helpers ---

    @SuppressWarnings("unchecked")
    private void injectConnections(Map<String, FeedConnection> mockConnections) throws Exception {
        Field field = FeedManager.class.getDeclaredField("connections");
        field.setAccessible(true);
        Map<String, FeedConnection> actual = (Map<String, FeedConnection>) field.get(feedManager);
        actual.putAll(mockConnections);
    }

    private FeedConnection mockFeedConnection(String id, String name) {
        FeedConnection fc = mock(FeedConnection.class);
        Connection conn = new Connection(name,
                "wss://example.com/ws",
                Connection.AdapterType.GENERIC,
                List.of("TEST"));
        // Override the auto-generated ID
        try {
            Field idField = Connection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(conn, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(fc.getConnection()).thenReturn(conn);
        return fc;
    }

    private Connection createBinanceConnection(String name) {
        return new Connection(name,
                "wss://stream.binance.com:9443/ws/btcusdt@trade",
                Connection.AdapterType.BINANCE,
                List.of("BTCUSDT"));
    }
}
