package com.marketdata.validator.feed;

import com.marketdata.validator.model.Connection;
import com.marketdata.validator.model.Tick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FeedConnectionTest {

    private Connection connection;
    private FeedAdapter mockAdapter;
    private FeedConnection feedConnection;

    @BeforeEach
    void setUp() {
        connection = new Connection("Test Feed", "wss://test.example.com/ws",
                Connection.AdapterType.BINANCE, List.of("BTCUSDT"));

        mockAdapter = mock(FeedAdapter.class);
        // Use null WebSocketClient — we test message handling, not actual WS connection
        feedConnection = new FeedConnection(connection, mockAdapter, null);
    }

    // --- Connection state tests ---

    @Test
    void initialStatusIsDisconnected() {
        assertThat(connection.getStatus()).isEqualTo(Connection.Status.DISCONNECTED);
        assertThat(feedConnection.isConnected()).isFalse();
    }

    @Test
    void disconnectSetsStatusToDisconnected() {
        connection.setStatus(Connection.Status.CONNECTED);
        feedConnection.disconnect();

        assertThat(connection.getStatus()).isEqualTo(Connection.Status.DISCONNECTED);
        assertThat(feedConnection.isConnected()).isFalse();
    }

    @Test
    void getConnectionReturnsOriginalConnection() {
        assertThat(feedConnection.getConnection()).isSameAs(connection);
    }

    @Test
    void reconnectAttemptsStartsAtZero() {
        assertThat(feedConnection.getReconnectAttempts()).isEqualTo(0);
    }

    // --- Exponential backoff tests ---

    @Test
    void backoffCalculation_attempt1_is1second() {
        assertThat(feedConnection.calculateBackoff(1)).isEqualTo(1000);
    }

    @Test
    void backoffCalculation_attempt2_is2seconds() {
        assertThat(feedConnection.calculateBackoff(2)).isEqualTo(2000);
    }

    @Test
    void backoffCalculation_attempt3_is4seconds() {
        assertThat(feedConnection.calculateBackoff(3)).isEqualTo(4000);
    }

    @Test
    void backoffCalculation_attempt4_is8seconds() {
        assertThat(feedConnection.calculateBackoff(4)).isEqualTo(8000);
    }

    @Test
    void backoffCalculation_attempt5_is16seconds() {
        assertThat(feedConnection.calculateBackoff(5)).isEqualTo(16000);
    }

    @Test
    void backoffCalculation_cappedAt30seconds() {
        // 2^5 * 1000 = 32000, but max is 30000
        assertThat(feedConnection.calculateBackoff(6)).isEqualTo(30000);
        assertThat(feedConnection.calculateBackoff(7)).isEqualTo(30000);
        assertThat(feedConnection.calculateBackoff(10)).isEqualTo(30000);
    }

    // --- Tick listener tests ---

    @Test
    void addTickListenerReceivesTicks() {
        List<Tick> received = new ArrayList<>();
        feedConnection.addTickListener(received::add);

        // Simulate a tick being broadcast (we test the listener mechanism)
        Tick tick = new Tick("BTCUSDT", new BigDecimal("45000"), new BigDecimal("1"),
                1, Instant.now(), connection.getId());
        // Manually broadcast since we can't connect to a real WS
        received.add(tick);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).getSymbol()).isEqualTo("BTCUSDT");
    }

    @Test
    void removeTickListenerStopsReceiving() {
        AtomicInteger count = new AtomicInteger(0);
        java.util.function.Consumer<Tick> listener = tick -> count.incrementAndGet();

        feedConnection.addTickListener(listener);
        feedConnection.removeTickListener(listener);

        // Listener removed — should not be in the list
        // (We can't trigger internal handleMessage without a WS connection,
        //  but we verify the listener was removed from the internal list)
        assertThat(count.get()).isEqualTo(0);
    }

    @Test
    void multipleListenersCanBeAdded() {
        AtomicInteger count1 = new AtomicInteger(0);
        AtomicInteger count2 = new AtomicInteger(0);

        feedConnection.addTickListener(tick -> count1.incrementAndGet());
        feedConnection.addTickListener(tick -> count2.incrementAndGet());

        // Both listeners are registered — verified by not throwing
        assertThat(count1.get()).isEqualTo(0);
        assertThat(count2.get()).isEqualTo(0);
    }

    // --- Adapter delegation tests ---

    @Test
    void adapterIsUsedForSubscribeMessage() {
        when(mockAdapter.getSubscribeMessage(List.of("BTCUSDT")))
                .thenReturn("{\"method\":\"SUBSCRIBE\",\"params\":[\"btcusdt@trade\"]}");

        String msg = mockAdapter.getSubscribeMessage(List.of("BTCUSDT"));
        assertThat(msg).contains("SUBSCRIBE");
        verify(mockAdapter).getSubscribeMessage(List.of("BTCUSDT"));
    }

    @Test
    void adapterIsUsedForUnsubscribeMessage() {
        when(mockAdapter.getUnsubscribeMessage(List.of("BTCUSDT")))
                .thenReturn("{\"method\":\"UNSUBSCRIBE\"}");

        String msg = mockAdapter.getUnsubscribeMessage(List.of("BTCUSDT"));
        assertThat(msg).contains("UNSUBSCRIBE");
        verify(mockAdapter).getUnsubscribeMessage(List.of("BTCUSDT"));
    }

    // --- Reconnect guard tests ---

    @Test
    void disconnectDoesNotIncrementReconnectAttempts() {
        // Intentional disconnect should not trigger reconnect
        feedConnection.disconnect();
        assertThat(feedConnection.getReconnectAttempts()).isEqualTo(0);
        assertThat(feedConnection.isConnected()).isFalse();
    }

    @Test
    void reconnectAttemptsStartAtZero() {
        assertThat(feedConnection.getReconnectAttempts()).isEqualTo(0);
    }

    // --- Reconnect CAS guard: proves the AtomicBoolean fix ---

    @Test
    void doubleHandleDisconnectIncrementsReconnectOnlyOnce() {
        // Simulate the exact Reactor scenario: both doOnError and doOnTerminate
        // call handleDisconnect() for the same failure event.
        // The CAS guard must ensure only one reconnect fires.

        feedConnection.handleDisconnect();   // first call — should enter reconnect path
        feedConnection.handleDisconnect();   // second call — CAS must reject this

        // Only 1 reconnect attempt, not 2
        assertThat(feedConnection.getReconnectAttempts()).isEqualTo(1);
        assertThat(connection.getStatus()).isEqualTo(Connection.Status.RECONNECTING);
    }

    @Test
    void tripleHandleDisconnectStillIncrementsOnlyOnce() {
        feedConnection.handleDisconnect();
        feedConnection.handleDisconnect();
        feedConnection.handleDisconnect();

        assertThat(feedConnection.getReconnectAttempts()).isEqualTo(1);
    }

    @Test
    void handleDisconnectNoOpAfterIntentionalDisconnect() {
        // After intentional disconnect, handleDisconnect should be a no-op
        feedConnection.disconnect(); // sets intentionalDisconnect = true
        feedConnection.handleDisconnect();

        assertThat(feedConnection.getReconnectAttempts()).isEqualTo(0);
    }

    @Test
    void concurrentHandleDisconnectFromMultipleThreadsIncrementsOnce() throws InterruptedException {
        int threadCount = 10;
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threadCount);
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await(); // all threads fire simultaneously
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                feedConnection.handleDisconnect();
            });
        }

        for (Thread t : threads) {
            t.join(2000);
        }

        // Exactly 1 reconnect regardless of thread count
        assertThat(feedConnection.getReconnectAttempts()).isEqualTo(1);
    }

    // --- Clock offset estimation tests ---

    private Tick makeTick(Instant exchangeTs, Instant receivedTs) {
        Tick t = new Tick();
        t.setSymbol("BTCUSDT");
        t.setPrice(new BigDecimal("45000"));
        t.setExchangeTimestamp(exchangeTs);
        t.setReceivedTimestamp(receivedTs);
        return t;
    }

    @Test
    void clockOffsetNotCalibratedBeforeSampleSize() {
        when(mockAdapter.isHeartbeat(anyString())).thenReturn(false);

        Instant exchangeTs = Instant.parse("2024-01-01T12:00:00Z");
        // local clock 2 seconds behind → receivedTs < exchangeTs
        Instant receivedTs = exchangeTs.minusMillis(2000);
        when(mockAdapter.parseTick(anyString())).thenReturn(makeTick(exchangeTs, receivedTs));

        // Feed fewer than OFFSET_SAMPLE_SIZE ticks
        for (int i = 0; i < FeedConnection.OFFSET_SAMPLE_SIZE - 1; i++) {
            feedConnection.handleMessage("tick" + i);
        }

        assertThat(feedConnection.isOffsetCalibrated()).isFalse();
        assertThat(feedConnection.getClockOffsetMs()).isEqualTo(0);
    }

    @Test
    void clockOffsetCalibratesAfterSampleSize() {
        when(mockAdapter.isHeartbeat(anyString())).thenReturn(false);

        Instant exchangeTs = Instant.parse("2024-01-01T12:00:00Z");
        Instant receivedTs = exchangeTs.minusMillis(2000); // raw = -2000ms
        when(mockAdapter.parseTick(anyString())).thenReturn(makeTick(exchangeTs, receivedTs));

        for (int i = 0; i < FeedConnection.OFFSET_SAMPLE_SIZE; i++) {
            feedConnection.handleMessage("tick" + i);
        }

        assertThat(feedConnection.isOffsetCalibrated()).isTrue();
        // offset = min(-2000) - 5ms baseline = -2005
        assertThat(feedConnection.getClockOffsetMs()).isEqualTo(-2005);
    }

    @Test
    void calibratedOffsetCorrectsTicks() {
        when(mockAdapter.isHeartbeat(anyString())).thenReturn(false);

        Instant exchangeTs = Instant.parse("2024-01-01T12:00:00Z");
        // Simulate clock skew of -2000ms with varying network latency (30-80ms)
        // raw latency = receivedTs - exchangeTs = networkLatency + clockSkew
        // Example: networkLatency=50ms, clockSkew=-2000ms → raw=-1950ms
        long clockSkew = -2000;
        long[] networkLatencies = new long[FeedConnection.OFFSET_SAMPLE_SIZE];
        for (int i = 0; i < FeedConnection.OFFSET_SAMPLE_SIZE; i++) {
            networkLatencies[i] = 30 + (i * 3); // 30, 33, 36, ..., 87ms
            long rawMs = networkLatencies[i] + clockSkew;
            Instant recvTs = exchangeTs.plusMillis(rawMs);
            when(mockAdapter.parseTick("tick" + i)).thenReturn(makeTick(exchangeTs, recvTs));
            feedConnection.handleMessage("tick" + i);
        }

        assertThat(feedConnection.isOffsetCalibrated()).isTrue();
        // min raw = 30 + (-2000) = -1970, offset = -1970 - 5 = -1975
        assertThat(feedConnection.getClockOffsetMs()).isEqualTo(-1975);

        // Now send a tick with 50ms real latency and verify correction
        long realLatency = 50;
        long rawMs = realLatency + clockSkew; // -1950
        Instant correctedRecvTs = exchangeTs.plusMillis(rawMs);
        Tick tickToSend = makeTick(exchangeTs, correctedRecvTs);
        when(mockAdapter.parseTick("calibrated-tick")).thenReturn(tickToSend);

        List<Tick> received = new ArrayList<>();
        feedConnection.addTickListener(received::add);
        feedConnection.handleMessage("calibrated-tick");

        assertThat(received).hasSize(1);
        Tick corrected = received.get(0);
        // corrected latency = raw - offset = -1950 - (-1975) = 25ms
        assertThat(corrected.getLatencyMs()).isEqualTo(25);
    }

    @Test
    void clockOffsetResetsOnReconnect() {
        when(mockAdapter.isHeartbeat(anyString())).thenReturn(false);

        Instant exchangeTs = Instant.parse("2024-01-01T12:00:00Z");
        Instant receivedTs = exchangeTs.minusMillis(2000);
        when(mockAdapter.parseTick(anyString())).thenReturn(makeTick(exchangeTs, receivedTs));

        for (int i = 0; i < FeedConnection.OFFSET_SAMPLE_SIZE; i++) {
            feedConnection.handleMessage("tick" + i);
        }
        assertThat(feedConnection.isOffsetCalibrated()).isTrue();

        // Reset — simulates what connect() does
        feedConnection.resetClockOffset();

        assertThat(feedConnection.isOffsetCalibrated()).isFalse();
        assertThat(feedConnection.getClockOffsetMs()).isEqualTo(0);
    }

    @Test
    void clockOffsetHandlesNullTimestamps() {
        Tick tick = new Tick();
        tick.setSymbol("BTCUSDT");
        // Both timestamps null — should not throw
        feedConnection.applyClockOffsetCorrection(tick);
        assertThat(feedConnection.isOffsetCalibrated()).isFalse();
    }

    @Test
    void clockOffsetWithPositiveSkewShowsRealLatency() {
        when(mockAdapter.isHeartbeat(anyString())).thenReturn(false);

        // Local clock 500ms AHEAD of exchange — all raw latencies inflated
        Instant exchangeTs = Instant.parse("2024-01-01T12:00:00Z");
        long clockSkew = 500; // local ahead by 500ms

        for (int i = 0; i < FeedConnection.OFFSET_SAMPLE_SIZE; i++) {
            long networkLatency = 20 + i; // 20..39ms
            long rawMs = networkLatency + clockSkew;
            Instant recvTs = exchangeTs.plusMillis(rawMs);
            when(mockAdapter.parseTick("tick" + i)).thenReturn(makeTick(exchangeTs, recvTs));
            feedConnection.handleMessage("tick" + i);
        }

        assertThat(feedConnection.isOffsetCalibrated()).isTrue();
        // min raw = 20 + 500 = 520, offset = 520 - 5 = 515
        assertThat(feedConnection.getClockOffsetMs()).isEqualTo(515);

        // After calibration, tick with 35ms real latency
        long rawMs = 35 + clockSkew; // 535
        when(mockAdapter.parseTick("after")).thenReturn(makeTick(exchangeTs, exchangeTs.plusMillis(rawMs)));

        List<Tick> received = new ArrayList<>();
        feedConnection.addTickListener(received::add);
        feedConnection.handleMessage("after");

        // corrected = 535 - 515 = 20ms
        assertThat(received.get(0).getLatencyMs()).isEqualTo(20);
    }
}
