package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Orchestrator that holds all validators and fans out each tick to all of them.
 *
 * Blueprint Section 5.3:
 *   - Holds all 9 validators (injected, not hardcoded)
 *   - Fans out each tick to all validators
 *   - Provides aggregated ValidationSummary (all results)
 *   - Publishes updates when results change
 *   - Supports reset and configuration propagation
 */
@Component
public class ValidatorEngine {

    private static final Logger log = LoggerFactory.getLogger(ValidatorEngine.class);

    // Throttle listener notifications to avoid overwhelming SSE and stalling the
    // BackpressureQueue consumer thread (Fix 9 — completeness false positives).
    private static final long NOTIFY_INTERVAL_MS = 250;

    private final List<Validator> validators;
    private final FieldCompletenessValidator fieldGate;
    private final List<Consumer<List<ValidationResult>>> listeners = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<String, List<ValidationResult>>> perFeedListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong tickCount = new AtomicLong(0);
    private volatile long lastNotifyTimeMs = 0;
    private ScheduledExecutorService throughputScheduler;

    // Per-feed validation scoping (Fix 8): feedId → isolated validator set + tick counter
    private final ConcurrentHashMap<String, List<Validator>> feedValidators = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> feedTickCounts = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ValidatorEngine(List<Validator> validators, FieldCompletenessValidator fieldGate) {
        this.validators = List.copyOf(validators); // Defensive copy — immutable after construction
        this.fieldGate = fieldGate;
    }

    /**
     * Convenience constructor for tests — no field-completeness gate.
     */
    public ValidatorEngine(List<Validator> validators) {
        this(validators, null);
    }

    /**
     * Start a 1-second timer that drives ThroughputValidator's per-second snapshot.
     * Also ticks per-feed ThroughputValidators so scoped throughput is non-zero (Fix 9b).
     * Only runs in Spring context (tests create ValidatorEngine directly, skipping this).
     */
    @PostConstruct
    void startThroughputTimer() {
        validators.stream()
                .filter(ThroughputValidator.class::isInstance)
                .map(ThroughputValidator.class::cast)
                .findFirst()
                .ifPresent(globalTv -> {
                    throughputScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "throughput-timer");
                        t.setDaemon(true);
                        return t;
                    });
                    throughputScheduler.scheduleAtFixedRate(() -> {
                        globalTv.tick();
                        // Drive per-feed ThroughputValidators too (Fix 9b)
                        for (List<Validator> perFeedVals : feedValidators.values()) {
                            for (Validator v : perFeedVals) {
                                if (v instanceof ThroughputValidator tv) {
                                    tv.tick();
                                }
                            }
                        }
                    }, 1, 1, TimeUnit.SECONDS);
                    log.info("ThroughputValidator timer started (1s interval, global + per-feed)");
                });
    }

    @PreDestroy
    void stopThroughputTimer() {
        if (throughputScheduler != null) {
            throughputScheduler.shutdownNow();
        }
    }

    /**
     * Process a single tick through all validators.
     * Called for every tick from every feed.
     * Fans out to both the global validator set and the per-feed validator set (Fix 8).
     */
    public void onTick(Tick tick) {
        if (fieldGate != null && !fieldGate.isValid(tick)) {
            return;
        }
        tickCount.incrementAndGet();
        if (tick.getTraceId() == null) {
            tick.setTraceId(java.util.UUID.randomUUID().toString());
        }
        fanOutToGlobalValidators(tick);
        List<Validator> perFeed = fanOutToPerFeedValidators(tick);
        maybeNotifyListeners(tick.getFeedId(), perFeed);
    }

    private void fanOutToGlobalValidators(Tick tick) {
        for (Validator v : validators) {
            try {
                v.onTick(tick);
                logValidationEvent(v, tick);
            } catch (Exception e) {
                log.error("Validator {} threw exception on tick seq={}: {}",
                        v.getArea(), tick.getSequenceNum(), e.getMessage(), e);
            }
        }
    }

    private List<Validator> fanOutToPerFeedValidators(Tick tick) {
        String feedId = tick.getFeedId();
        if (feedId == null || feedId.isBlank()) {
            return Collections.emptyList();
        }
        feedTickCounts.computeIfAbsent(feedId, k -> new AtomicLong(0)).incrementAndGet();
        List<Validator> perFeed = feedValidators.computeIfAbsent(feedId, k -> createValidatorSet());
        for (Validator v : perFeed) {
            try {
                v.onTick(tick);
            } catch (Exception e) {
                log.error("Per-feed validator {} threw on tick seq={} feedId={}: {}",
                        v.getArea(), tick.getSequenceNum(), feedId, e.getMessage(), e);
            }
        }
        return perFeed;
    }

    private void maybeNotifyListeners(String feedId, List<Validator> perFeed) {
        long now = System.currentTimeMillis();
        if (now - lastNotifyTimeMs >= NOTIFY_INTERVAL_MS) {
            lastNotifyTimeMs = now;
            notifyListeners();
            if (!perFeed.isEmpty()) {
                notifyPerFeedListeners(feedId, perFeed);
            }
        }
    }

    /**
     * Get results from all validators as a list.
     */
    public List<ValidationResult> getResults() {
        return validators.stream()
                .map(Validator::getResult)
                .toList();
    }

    /**
     * Get a map of area → result for quick lookup.
     */
    public Map<String, ValidationResult> getResultsByArea() {
        return validators.stream()
                .collect(Collectors.toMap(Validator::getArea, Validator::getResult));
    }

    /**
     * Get results for a specific feed (Fix 8).
     * Returns an empty list if no ticks have been seen for this feedId.
     */
    public List<ValidationResult> getResults(String feedId) {
        List<Validator> perFeed = feedValidators.get(feedId);
        if (perFeed == null) return Collections.emptyList();
        return perFeed.stream().map(Validator::getResult).toList();
    }

    /**
     * Get area → result map for a specific feed (Fix 8).
     * Returns an empty map if no ticks have been seen for this feedId.
     */
    public Map<String, ValidationResult> getResultsByArea(String feedId) {
        List<Validator> perFeed = feedValidators.get(feedId);
        if (perFeed == null) return Collections.emptyMap();
        return perFeed.stream().collect(Collectors.toMap(Validator::getArea, Validator::getResult));
    }

    /**
     * Get the tick count for a specific feed (Fix 8).
     */
    public long getTickCount(String feedId) {
        AtomicLong counter = feedTickCounts.get(feedId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Get the set of feedIds that have been seen so far (Fix 8).
     */
    public Set<String> getKnownFeedIds() {
        return Set.copyOf(feedValidators.keySet());
    }

    /**
     * Reset all validators — clears all state (global + per-feed).
     */
    public void reset() {
        validators.forEach(Validator::reset);
        tickCount.set(0);
        lastNotifyTimeMs = 0;
        feedValidators.clear();
        feedTickCounts.clear();
        if (fieldGate != null) fieldGate.reset();
        log.info("All validators reset (global + per-feed)");
    }

    /**
     * Alias for {@link #reset()} — used by SimulatorController to signal a clean session start.
     */
    public void resetAllValidatorState() {
        reset();
    }

    /**
     * Propagate configuration to all validators.
     */
    public void configure(Map<String, Object> config) {
        validators.forEach(v -> v.configure(config));
    }

    /**
     * Propagate configuration to a specific validator by area name.
     */
    public void configure(String area, Map<String, Object> config) {
        validators.stream()
                .filter(v -> v.getArea().equals(area))
                .findFirst()
                .ifPresent(v -> v.configure(config));
    }

    /**
     * Register a listener that's notified after every tick is processed.
     */
    public void addListener(Consumer<List<ValidationResult>> listener) {
        listeners.add(listener);
    }

    /**
     * Register a per-feed listener that receives (feedId, results) after each throttle window.
     * Used by AlertGenerator to generate alerts from per-feed validator failures.
     */
    public void addPerFeedListener(BiConsumer<String, List<ValidationResult>> listener) {
        perFeedListeners.add(listener);
    }

    /**
     * Remove a previously registered listener.
     */
    public void removeListener(Consumer<List<ValidationResult>> listener) {
        listeners.remove(listener);
    }

    /**
     * Get total number of ticks processed (passed field-completeness gate).
     */
    public long getTickCount() {
        return tickCount.get();
    }

    /**
     * Get count of ticks rejected by the pre-validation field-completeness gate.
     */
    public long getRejectedCount() {
        return fieldGate != null ? fieldGate.getRejectedCount() : 0;
    }

    /**
     * Get count of duplicate ticks detected by the ordering validator.
     */
    public long getDuplicateCount() {
        return validators.stream()
                .filter(OrderingValidator.class::isInstance)
                .map(OrderingValidator.class::cast)
                .map(OrderingValidator::getDuplicateCount)
                .findFirst()
                .orElse(0L);
    }

    /**
     * Get count of registered validators.
     */
    public int getValidatorCount() {
        return validators.size();
    }

    /**
     * Get the global ReconnectionValidator instance, or null if not present.
     * Used by FeedManager to wire disconnect/reconnect events.
     */
    public ReconnectionValidator getReconnectionValidator() {
        return validators.stream()
                .filter(ReconnectionValidator.class::isInstance)
                .map(ReconnectionValidator.class::cast)
                .findFirst()
                .orElse(null);
    }

    /**
     * Check if the overall status has any FAIL results.
     */
    public boolean hasFailures() {
        return validators.stream()
                .anyMatch(v -> v.getResult().getStatus() == ValidationResult.Status.FAIL);
    }

    /**
     * Log a structured validation event.
     * WARN/FAIL results are logged at WARN level; PASS at DEBUG to avoid noise at high throughput.
     * In JSON mode (prod), each field becomes a top-level searchable key.
     * Blueprint Section 16: "Every validation result logged as JSON — includes:
     * timestamp, validator, symbol, status, value, threshold"
     */
    private void logValidationEvent(Validator v, Tick tick) {
        ValidationResult result = v.getResult();
        if (result.getStatus() == ValidationResult.Status.PASS) {
            log.debug("Validation PASS: {} {} {}",
                    StructuredArguments.keyValue("validator", result.getArea()),
                    StructuredArguments.keyValue("symbol", tick.getSymbol()),
                    StructuredArguments.keyValue("status", result.getStatus()));
        } else {
            log.warn("Validation {}: {} {} {} {} {}",
                    result.getStatus(),
                    StructuredArguments.keyValue("validator", result.getArea()),
                    StructuredArguments.keyValue("symbol", tick.getSymbol()),
                    StructuredArguments.keyValue("status", result.getStatus()),
                    StructuredArguments.keyValue("value", result.getMetric()),
                    StructuredArguments.keyValue("threshold", result.getThreshold()));
        }
    }

    private void notifyListeners() {
        if (listeners.isEmpty()) {
            return;
        }
        List<ValidationResult> results = getResults();
        for (Consumer<List<ValidationResult>> listener : listeners) {
            try {
                listener.accept(results);
            } catch (Exception e) {
                log.error("Listener threw exception: {}", e.getMessage(), e);
            }
        }
    }

    private void notifyPerFeedListeners(String feedId, List<Validator> perFeed) {
        if (perFeedListeners.isEmpty()) {
            return;
        }
        List<ValidationResult> results = perFeed.stream()
                .map(Validator::getResult)
                .toList();
        for (BiConsumer<String, List<ValidationResult>> listener : perFeedListeners) {
            try {
                listener.accept(feedId, results);
            } catch (Exception e) {
                log.error("Per-feed listener threw exception: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Create a fresh validator set for per-feed scoping (Fix 8).
     * Instantiates the same 9 validator types as the global set.
     */
    private List<Validator> createValidatorSet() {
        return List.of(
                new AccuracyValidator(),
                new OrderingValidator(),
                new LatencyValidator(),
                new CompletenessValidator(),
                new ThroughputValidator(),
                new ReconnectionValidator(),
                new SubscriptionValidator(),
                new StatefulValidator(),
                new SourceValidator()
        );
    }
}
