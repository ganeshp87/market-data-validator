package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Orchestrator that holds all validators and fans out each tick to all of them.
 *
 * Blueprint Section 5.3:
 *   - Holds all 8 validators (injected, not hardcoded)
 *   - Fans out each tick to all validators
 *   - Provides aggregated ValidationSummary (all results)
 *   - Publishes updates when results change
 *   - Supports reset and configuration propagation
 */
@Component
public class ValidatorEngine {

    private static final Logger log = LoggerFactory.getLogger(ValidatorEngine.class);

    private final List<Validator> validators;
    private final List<Consumer<List<ValidationResult>>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong tickCount = new AtomicLong(0);

    public ValidatorEngine(List<Validator> validators) {
        this.validators = List.copyOf(validators); // Defensive copy — immutable after construction
    }

    /**
     * Process a single tick through all validators.
     * Called for every tick from every feed.
     */
    public void onTick(Tick tick) {
        tickCount.incrementAndGet();

        // Assign traceId for end-to-end tracing through the validation pipeline
        if (tick.getTraceId() == null) {
            tick.setTraceId(java.util.UUID.randomUUID().toString());
        }

        for (Validator v : validators) {
            try {
                v.onTick(tick);
                logValidationEvent(v, tick);
            } catch (Exception e) {
                log.error("Validator {} threw exception on tick seq={}: {}",
                        v.getArea(), tick.getSequenceNum(), e.getMessage(), e);
                // Don't let one broken validator stop others
            }
        }

        notifyListeners();
    }

    /**
     * Get results from all validators as a list.
     */
    public List<ValidationResult> getResults() {
        return validators.stream()
                .map(Validator::getResult)
                .collect(Collectors.toList());
    }

    /**
     * Get a map of area → result for quick lookup.
     */
    public Map<String, ValidationResult> getResultsByArea() {
        return validators.stream()
                .collect(Collectors.toMap(Validator::getArea, Validator::getResult));
    }

    /**
     * Reset all validators — clears all state.
     */
    public void reset() {
        validators.forEach(Validator::reset);
        tickCount.set(0);
        log.info("All validators reset");
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
     * Remove a previously registered listener.
     */
    public void removeListener(Consumer<List<ValidationResult>> listener) {
        listeners.remove(listener);
    }

    /**
     * Get total number of ticks processed.
     */
    public long getTickCount() {
        return tickCount.get();
    }

    /**
     * Get count of registered validators.
     */
    public int getValidatorCount() {
        return validators.size();
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
}
