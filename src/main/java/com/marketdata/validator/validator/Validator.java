package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;

import java.util.Map;

/**
 * Interface for all 9 validators in the system.
 * Each validator receives every tick, maintains its own state,
 * and produces a ValidationResult on demand.
 *
 * Validators must be:
 * - O(1) per tick (no sorting, no unbounded lists)
 * - Bounded memory (sliding windows, counters, not raw storage)
 * - Idempotent (skip already-processed sequence numbers)
 */
public interface Validator {

    /**
     * The validation area this validator covers (e.g., "ACCURACY", "ORDERING").
     */
    String getArea();

    /**
     * Process a single tick. Called for every tick from every feed.
     * Must be O(1) — no sorting, no growing lists.
     */
    void onTick(Tick tick);

    /**
     * Get the current validation result (snapshot of current state).
     */
    ValidationResult getResult();

    /**
     * Reset all state — used when starting a new session or replaying.
     */
    void reset();

    /**
     * Configure thresholds (e.g., latency threshold, accuracy threshold).
     */
    void configure(Map<String, Object> config);
}
