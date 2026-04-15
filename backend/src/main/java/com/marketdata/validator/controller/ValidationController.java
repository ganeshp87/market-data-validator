package com.marketdata.validator.controller;

import com.marketdata.validator.feed.FeedManager;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.validator.ValidatorEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST controller for validation configuration and results.
 *
 * Endpoints:
 *   GET  /api/validation/summary  — current validation state (all validators)
 *   GET  /api/validation/history  — validation snapshots over time
 *   PUT  /api/validation/config   — update thresholds
 *   POST /api/validation/reset    — reset all validator state
 */
@RestController
@RequestMapping("/api/validation")
public class ValidationController {

    private final ValidatorEngine engine;
    private final FeedManager feedManager;

    public ValidationController(ValidatorEngine engine, FeedManager feedManager) {
        this.engine = engine;
        this.feedManager = feedManager;
    }

    /**
     * Current validation state — all validators' latest results plus overall status.
     * Optional query param ?feedId= scopes results to a single feed (Fix 8).
     */
    @GetMapping("/summary")
    public Map<String, Object> getSummary(
            @RequestParam(required = false) String feedId) {

        Map<String, ValidationResult> resultsByArea;
        long ticks;

        if (feedId != null && !feedId.isBlank()) {
            resultsByArea = engine.getResultsByArea(feedId);
            ticks = engine.getTickCount(feedId);
            if (resultsByArea == null) {
                resultsByArea = Map.of();
            }
        } else {
            resultsByArea = engine.getResultsByArea();
            ticks = engine.getTickCount();
        }

        ValidationResult.Status overallStatus = computeOverallStatus(resultsByArea);

        return Map.of(
                "results", resultsByArea,
                "overallStatus", overallStatus,
                "timestamp", Instant.now(),
                "ticksProcessed", ticks,
                "rejectedCount", engine.getRejectedCount(),
                "duplicateCount", engine.getDuplicateCount()
        );
    }

    /**
     * Validation history — list of all current results as an ordered list.
     * Each call returns the current snapshot.
     */
    @GetMapping("/history")
    public List<ValidationResult> getHistory() {
        return engine.getResults();
    }

    /**
     * Update validation thresholds.
     * Body can target a specific area or apply globally:
     *   { "area": "LATENCY", "config": { "warnThresholdMs": 200 } }
     * or:
     *   { "config": { "warnThresholdMs": 200 } }
     */
    @PutMapping("/config")
    public ResponseEntity<?> updateConfig(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) body.get("config");
        if (config == null || config.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "'config' map is required"));
        }

        String area = (String) body.get("area");
        if (area != null) {
            engine.configure(area, config);
        } else {
            engine.configure(config);
        }

        return ResponseEntity.ok(Map.of(
                "message", "Configuration updated",
                "area", area != null ? area : "ALL"
        ));
    }

    /**
     * Reset all validator state — clears counters, buffers, results.
     */
    @PostMapping("/reset")
    public Map<String, Object> resetAll() {
        engine.reset();
        return Map.of(
                "message", "All validators reset",
                "timestamp", Instant.now()
        );
    }

    /**
     * GET /api/validation/feeds — list configured feeds with id and name (Fix 8).
     * Used by the frontend scope selector dropdown.
     */
    @GetMapping("/feeds")
    public List<Map<String, String>> getFeeds() {
        return feedManager.getAllConnections().stream()
                .map(c -> Map.of("id", c.getId(), "name", c.getName()))
                .toList();
    }

    /**
     * Computes the worst status across all validators.
     * FAIL > WARN > PASS — if any validator is FAIL, overall is FAIL.
     */
    private ValidationResult.Status computeOverallStatus(
            Map<String, ValidationResult> results) {
        boolean hasFail = false;
        boolean hasWarn = false;

        for (ValidationResult result : results.values()) {
            if (result.getStatus() == ValidationResult.Status.FAIL) {
                hasFail = true;
            } else if (result.getStatus() == ValidationResult.Status.WARN) {
                hasWarn = true;
            }
        }

        if (hasFail) return ValidationResult.Status.FAIL;
        if (hasWarn) return ValidationResult.Status.WARN;
        return ValidationResult.Status.PASS;
    }
}
