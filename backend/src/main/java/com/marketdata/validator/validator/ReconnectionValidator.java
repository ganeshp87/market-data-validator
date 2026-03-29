package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.model.ValidationResult.Area;
import com.marketdata.validator.model.ValidationResult.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Validates connection resilience by tracking reconnection events.
 *
 * Blueprint Section 9.4:
 *   - Tracks disconnect/reconnect counts
 *   - Measures reconnection durations
 *   - Detects failed reconnects and subscription restoration
 *
 * Result thresholds:
 *   PASS: reconnectCount == disconnectCount AND avg(reconnectTimes) < 5s
 *   WARN: reconnectCount < disconnectCount OR avg(reconnectTimes) > 5s
 *   FAIL: failedReconnects > 0
 *
 * Note: This validator is event-driven, not tick-driven.
 * onTick() is a no-op. Connection events are reported via dedicated methods.
 */
@Component
public class ReconnectionValidator implements Validator {

    private static final Logger log = LoggerFactory.getLogger(ReconnectionValidator.class);
    private static final long DEFAULT_RECONNECT_THRESHOLD_MS = 5_000; // 5 seconds

    private final AtomicLong disconnectCount = new AtomicLong(0);
    private final AtomicLong reconnectCount = new AtomicLong(0);
    private final AtomicLong failedReconnects = new AtomicLong(0);
    private final AtomicLong subscriptionRestorations = new AtomicLong(0);
    private final List<Long> reconnectTimesMs = new CopyOnWriteArrayList<>();

    private long reconnectThresholdMs = DEFAULT_RECONNECT_THRESHOLD_MS;

    @Override
    public String getArea() {
        return Area.RECONNECTION.name();
    }

    /**
     * Ticks don't drive this validator — connection events do.
     * onTick is a no-op to satisfy the Validator interface.
     */
    @Override
    public void onTick(Tick tick) {
        // No-op: reconnection validation is event-driven, not tick-driven
    }

    /**
     * Record a connection disconnect event.
     */
    public void onDisconnect(String connectionId) {
        disconnectCount.incrementAndGet();
        log.debug("Disconnect recorded for connection={}", connectionId);
    }

    /**
     * Record a successful reconnection with the time it took.
     */
    public void onReconnect(String connectionId, Duration reconnectTime) {
        reconnectCount.incrementAndGet();
        reconnectTimesMs.add(reconnectTime.toMillis());
        log.debug("Reconnect recorded for connection={} took={}ms",
                connectionId, reconnectTime.toMillis());
    }

    /**
     * Record a failed reconnection attempt (max retries exhausted).
     */
    public void onReconnectFailed(String connectionId) {
        failedReconnects.incrementAndGet();
        log.warn("Reconnect FAILED for connection={}", connectionId);
    }

    /**
     * Record that subscriptions were successfully restored after reconnect.
     */
    public void onSubscriptionRestored(String connectionId) {
        subscriptionRestorations.incrementAndGet();
        log.debug("Subscriptions restored for connection={}", connectionId);
    }

    @Override
    public ValidationResult getResult() {
        long disconnects = disconnectCount.get();
        long reconnects = reconnectCount.get();
        long failed = failedReconnects.get();
        long restored = subscriptionRestorations.get();

        if (disconnects == 0 && reconnects == 0 && failed == 0) {
            return ValidationResult.pass(Area.RECONNECTION,
                    "No connection events yet", 100.0, reconnectThresholdMs);
        }

        double avgReconnectMs = reconnectTimesMs.isEmpty() ? 0.0 :
                reconnectTimesMs.stream().mapToLong(Long::longValue).average().orElse(0.0);

        Status status;
        if (failed > 0) {
            status = Status.FAIL;
        } else if (reconnects < disconnects || avgReconnectMs > reconnectThresholdMs) {
            status = Status.WARN;
        } else {
            status = Status.PASS;
        }

        String message = String.format(
                "disconnects=%d, reconnects=%d, failed=%d, avgReconnectTime=%.0fms, restorations=%d",
                disconnects, reconnects, failed, avgReconnectMs, restored);

        double metric = disconnects == 0 ? 100.0 : 100.0 * reconnects / disconnects;

        ValidationResult result = new ValidationResult(Area.RECONNECTION, status,
                message, metric, reconnectThresholdMs);
        result.getDetails().put("disconnectCount", disconnects);
        result.getDetails().put("reconnectCount", reconnects);
        result.getDetails().put("failedReconnects", failed);
        result.getDetails().put("avgReconnectTimeMs", avgReconnectMs);
        result.getDetails().put("subscriptionRestorations", restored);

        return result;
    }

    @Override
    public void reset() {
        disconnectCount.set(0);
        reconnectCount.set(0);
        failedReconnects.set(0);
        subscriptionRestorations.set(0);
        reconnectTimesMs.clear();
    }

    @Override
    public void configure(Map<String, Object> config) {
        if (config.containsKey("reconnectThresholdMs")) {
            reconnectThresholdMs = ((Number) config.get("reconnectThresholdMs")).longValue();
        }
    }

    // --- Visible for testing ---

    long getDisconnectCount() {
        return disconnectCount.get();
    }

    long getReconnectCount() {
        return reconnectCount.get();
    }

    long getFailedReconnects() {
        return failedReconnects.get();
    }

    long getSubscriptionRestorations() {
        return subscriptionRestorations.get();
    }

    double getAvgReconnectTimeMs() {
        if (reconnectTimesMs.isEmpty()) return 0.0;
        return reconnectTimesMs.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }
}
