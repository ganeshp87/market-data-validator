package com.marketdata.validator.feed;

import com.marketdata.validator.model.Connection;
import com.marketdata.validator.model.Tick;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Manages a single WebSocket connection to a market data feed.
 * Handles: connect, disconnect, auto-reconnect with exponential backoff,
 * message routing through FeedAdapter, and tick broadcasting.
 */
public class FeedConnection {

    private static final Logger log = LoggerFactory.getLogger(FeedConnection.class);
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long MAX_BACKOFF_MS = 30_000;
    static final int OFFSET_SAMPLE_SIZE = 20;
    // Assumed minimum one-way network latency (ms). Subtracted from the min sample
    // so the offset compensates for clock skew only, not network delay.
    static final long ASSUMED_MIN_NETWORK_MS = 5;

    private final Connection connection;
    private final FeedAdapter adapter;
    private final WebSocketClient webSocketClient;
    private final List<Consumer<Tick>> tickListeners = new CopyOnWriteArrayList<>();

    private Disposable wsSession;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private volatile boolean intentionalDisconnect = false;

    // Reconnection lifecycle callbacks — wired by FeedManager to drive ReconnectionValidator
    private final AtomicReference<Runnable> disconnectCallback = new AtomicReference<>();
    private final AtomicReference<Consumer<Duration>> reconnectCallback = new AtomicReference<>();
    private final AtomicReference<Runnable> reconnectFailedCallback = new AtomicReference<>();
    private final AtomicReference<Instant> disconnectTime = new AtomicReference<>();

    // Clock offset estimation: adjusts for skew between local clock and exchange
    private final long[] offsetSamples = new long[OFFSET_SAMPLE_SIZE];
    private int offsetSampleCount = 0;
    private volatile long clockOffsetMs = 0;
    private volatile boolean offsetCalibrated = false;

    public FeedConnection(Connection connection, FeedAdapter adapter) {
        this(connection, adapter, new ReactorNettyWebSocketClient());
    }

    /**
     * Constructor with injectable WebSocketClient — used for testing.
     */
    public FeedConnection(Connection connection, FeedAdapter adapter,
                          WebSocketClient webSocketClient) {
        this.connection = connection;
        this.adapter = adapter;
        this.webSocketClient = webSocketClient;
    }

    /**
     * Connect to the WebSocket feed and start receiving messages.
     */
    public void connect() {
        intentionalDisconnect = false;
        reconnecting.set(false);
        resetClockOffset();
        doConnect();
    }

    /**
     * Intentionally disconnect — no auto-reconnect.
     */
    public void disconnect() {
        intentionalDisconnect = true;
        if (wsSession != null && !wsSession.isDisposed()) {
            wsSession.dispose();
        }
        connection.setStatus(Connection.Status.DISCONNECTED);
        log.info("Disconnected from feed: {}", connection.getName());
    }

    /**
     * Register a listener that receives every parsed Tick.
     */
    public void addTickListener(Consumer<Tick> listener) {
        tickListeners.add(listener);
    }

    /**
     * Remove a tick listener.
     */
    public void removeTickListener(Consumer<Tick> listener) {
        tickListeners.remove(listener);
    }

    public void setDisconnectCallback(Runnable cb) { this.disconnectCallback.set(cb); }
    public void setReconnectCallback(Consumer<Duration> cb) { this.reconnectCallback.set(cb); }
    public void setReconnectFailedCallback(Runnable cb) { this.reconnectFailedCallback.set(cb); }

    public Connection getConnection() {
        return connection;
    }

    public int getReconnectAttempts() {
        return reconnectAttempts.get();
    }

    public boolean isConnected() {
        return connection.getStatus() == Connection.Status.CONNECTED;
    }

    // --- Internal ---

    private void doConnect() {
        URI uri = URI.create(connection.getUrl());
        log.info("Connecting to feed: {} {}",
                StructuredArguments.keyValue("feed", connection.getName()),
                StructuredArguments.keyValue("url", uri));

        wsSession = webSocketClient.execute(uri, session -> {
            // On connect: mark connected, reset reconnect counter, subscribe
            connection.setStatus(Connection.Status.CONNECTED);
            connection.setConnectedAt(Instant.now());
            reconnectAttempts.set(0);
            log.info("Connected to feed: {} {}",
                    StructuredArguments.keyValue("feed", connection.getName()),
                    StructuredArguments.keyValue("event", "connected"));

            // Notify reconnect lifecycle if this was a recovery (disconnectTime set by handleDisconnect)
            Instant dt = disconnectTime.get();
            if (dt != null) {
                disconnectTime.set(null);
                Consumer<Duration> rcb = reconnectCallback.get();
                if (rcb != null) rcb.accept(Duration.between(dt, Instant.now()));
            }

            // Send subscribe message
            String subscribeMsg = adapter.getSubscribeMessage(connection.getSymbols());
            Mono<Void> send = session.send(
                    Mono.just(session.textMessage(subscribeMsg)));

            // Receive messages
            Mono<Void> receive = session.receive()
                    .map(WebSocketMessage::getPayloadAsText)
                    .doOnNext(this::handleMessage)
                    .then();

            return send.thenMany(receive).then();
        })
        .doOnError(error -> {
            log.warn("WebSocket error for {} {} {}",
                    StructuredArguments.keyValue("feed", connection.getName()),
                    StructuredArguments.keyValue("event", "ws_error"),
                    StructuredArguments.keyValue("error", error.getMessage()));
            handleDisconnect();
        })
        .doOnTerminate(() -> {
            if (!intentionalDisconnect) {
                handleDisconnect();
            }
        })
        .subscribe();
    }

    void handleMessage(String rawMessage) {
        if (adapter.isHeartbeat(rawMessage)) {
            return;
        }

        Tick tick = adapter.parseTick(rawMessage);
        if (tick == null) {
            return;
        }

        tick.setFeedId(connection.getId());
        connection.recordTick();
        applyClockOffsetCorrection(tick);

        // Broadcast to all listeners
        for (Consumer<Tick> listener : tickListeners) {
            try {
                listener.accept(tick);
            } catch (Exception e) {
                log.error("Tick listener error: {}", e.getMessage());
            }
        }
    }

    /**
     * Estimates clock offset from the first N ticks.
     * Uses min(rawLatency) - ASSUMED_MIN_NETWORK_MS so the offset removes only
     * the clock skew component, preserving real network latency in the output.
     */
    void applyClockOffsetCorrection(Tick tick) {
        if (tick.getExchangeTimestamp() == null || tick.getReceivedTimestamp() == null) {
            return;
        }

        long rawMs = Duration.between(tick.getExchangeTimestamp(), tick.getReceivedTimestamp()).toMillis();

        if (!offsetCalibrated) {
            offsetSamples[offsetSampleCount++] = rawMs;
            if (offsetSampleCount >= OFFSET_SAMPLE_SIZE) {
                long minRaw = Arrays.stream(offsetSamples).min().orElse(0);
                clockOffsetMs = minRaw - ASSUMED_MIN_NETWORK_MS;
                offsetCalibrated = true;
                log.info("Clock offset calibrated: {}ms (minRaw={}ms, baseline={}ms) from {} samples {}",
                        clockOffsetMs, minRaw, ASSUMED_MIN_NETWORK_MS, OFFSET_SAMPLE_SIZE,
                        StructuredArguments.keyValue("event", "clock_offset_calibrated"));
            }
        }

        if (clockOffsetMs != 0) {
            tick.setReceivedTimestamp(tick.getReceivedTimestamp().minusMillis(clockOffsetMs));
        }
    }

    void resetClockOffset() {
        offsetSampleCount = 0;
        clockOffsetMs = 0;
        offsetCalibrated = false;
    }

    long getClockOffsetMs() {
        return clockOffsetMs;
    }

    boolean isOffsetCalibrated() {
        return offsetCalibrated;
    }

    /**
     * Handle an unexpected disconnection. Applies CAS guard to prevent
     * duplicate reconnects when both doOnError and doOnTerminate fire.
     * Package-visible for testing the reconnect guard.
     */
    void handleDisconnect() {
        if (intentionalDisconnect) {
            return;
        }

        // Guard: only one reconnect per disconnect event (doOnError + doOnTerminate both fire)
        if (!reconnecting.compareAndSet(false, true)) {
            return;
        }

        // Record when the disconnect happened and notify the reconnection validator
        disconnectTime.set(Instant.now());
        Runnable dcb = disconnectCallback.get();
        if (dcb != null) dcb.run();

        // Different exchange servers may have different clock offsets — recalibrate after reconnect
        // so latency metrics are not silently skewed by a stale offset from the previous server.
        resetClockOffset();

        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            connection.setStatus(Connection.Status.ERROR);
            log.error("Feed {} failed after {} reconnect attempts {}",
                    StructuredArguments.keyValue("feed", connection.getName()),
                    MAX_RECONNECT_ATTEMPTS,
                    StructuredArguments.keyValue("event", "reconnect_exhausted"));
            Runnable rfcb = reconnectFailedCallback.get();
            if (rfcb != null) rfcb.run();
            return;
        }

        connection.setStatus(Connection.Status.RECONNECTING);
        long backoffMs = calculateBackoff(attempt);
        log.info("Reconnecting {} in {}ms (attempt {}/{}) {}",
                StructuredArguments.keyValue("feed", connection.getName()),
                backoffMs, attempt, MAX_RECONNECT_ATTEMPTS,
                StructuredArguments.keyValue("event", "reconnecting"));

        // Schedule reconnect on a virtual thread (or daemon thread)
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(backoffMs);
                if (!intentionalDisconnect) {
                    reconnecting.set(false);
                    doConnect();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Exponential backoff: min(2^attempt * 1000, 30000) ms.
     * Visible for testing.
     */
    long calculateBackoff(int attempt) {
        long backoff = (long) (Math.pow(2, (double) attempt - 1) * 1000);
        return Math.min(backoff, MAX_BACKOFF_MS);
    }
}
