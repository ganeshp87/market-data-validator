package com.marketdata.validator.validator;

import com.marketdata.validator.model.Alert;
import com.marketdata.validator.model.Alert.Severity;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.store.AlertStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Watches validation results and generates alerts when a validator is in
 * FAIL or WARN state and no unacknowledged alert exists for that area.
 *
 * Uses a time-based throttle to avoid querying the database on every tick.
 * Re-checks every {@code CHECK_INTERVAL_MS} milliseconds so that if a user
 * deletes or acknowledges an alert while the failure persists, a new alert
 * is generated automatically.
 */
@Component
public class AlertGenerator {

    private static final Logger log = LoggerFactory.getLogger(AlertGenerator.class);
    private static final long CHECK_INTERVAL_MS = 10_000; // re-check every 10 seconds

    private final ValidatorEngine engine;
    private final AlertStore alertStore;

    private final AtomicLong lastCheckTime = new AtomicLong(0);
    // Areas that already have an unacknowledged alert — refreshed periodically
    private volatile Set<String> coveredAreas = ConcurrentHashMap.newKeySet();
    private volatile boolean initialized = false;

    public AlertGenerator(ValidatorEngine engine, AlertStore alertStore) {
        this.engine = engine;
        this.alertStore = alertStore;
    }

    /** Package-private for testing — returns the raw lastCheckTime value. */
    long getLastCheckTime() {
        return lastCheckTime.get();
    }

    @PostConstruct
    void init() {
        // Clear any stale alerts from previous server runs so fresh alerts are generated
        try {
            alertStore.deleteAll();
            log.info("Cleared stale alerts from previous run");
        } catch (Exception e) {
            log.warn("Failed to clear stale alerts: {}", e.getMessage());
        }
        engine.addListener(this::onValidationResults);
        engine.addPerFeedListener((feedId, results) -> onValidationResults(results));
        log.info("AlertGenerator registered as ValidatorEngine listener (global + per-feed)");
    }

    void onValidationResults(List<ValidationResult> results) {
        long now = System.currentTimeMillis();
        long lastCheck = lastCheckTime.get();

        // Refresh the set of areas that already have unacknowledged alerts.
        // The timer is advanced only after a successful query — if the query throws,
        // lastCheckTime is reset to lastCheck so the next call retries immediately.
        if (now - lastCheck >= CHECK_INTERVAL_MS && lastCheckTime.compareAndSet(lastCheck, now)) {
            try {
                coveredAreas = alertStore.findUnacknowledged().stream()
                        .map(Alert::getArea)
                        .collect(Collectors.toSet());
            } catch (Exception e) {
                lastCheckTime.set(lastCheck); // reset timer — retry on next call
                log.warn("Failed to refresh covered alert areas: {}", e.getMessage());
            }
        }

        for (ValidationResult result : results) {
            if (result.getStatus() == ValidationResult.Status.PASS) {
                continue;
            }

            String areaName = result.getArea().name();

            // Skip if an unacknowledged alert already exists for this area
            if (coveredAreas.contains(areaName)) {
                continue;
            }

            Severity severity = result.getStatus() == ValidationResult.Status.FAIL
                    ? Severity.CRITICAL : Severity.WARN;

            String message = String.format("%s validation %s — value: %.2f, threshold: %.2f",
                    areaName, result.getStatus(), result.getMetric(), result.getThreshold());

            Alert alert = new Alert(areaName, severity, message);
            alertStore.save(alert);
            coveredAreas.add(areaName); // mark as covered immediately
            log.info("Alert generated: [{}] {} — {}", severity, areaName, message);
        }
    }
}
