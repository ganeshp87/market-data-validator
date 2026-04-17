package com.marketdata.validator.controller;

import com.marketdata.validator.feed.FeedManager;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.validator.BackpressureQueue;
import com.marketdata.validator.validator.ValidatorEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes system metrics as JSON — ready for Prometheus scraping or dashboard polling.
 *
 * Blueprint Section 16:
 *   GET /api/metrics → JSON metrics
 *   tick_count_total, validation_pass_rate, latency_p95
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final ValidatorEngine engine;
    private final BackpressureQueue queue;
    private final FeedManager feedManager;

    public MetricsController(ValidatorEngine engine,
                             BackpressureQueue queue,
                             FeedManager feedManager) {
        this.engine = engine;
        this.queue = queue;
        this.feedManager = feedManager;
    }

    @GetMapping
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("timestamp", Instant.now().toString());

        // Tick processing metrics
        metrics.put("tick_count_total", engine.getTickCount());

        // Validation pass rate
        long total = engine.getResults().size();
        long passing = engine.getResults().stream()
                .filter(r -> r.getStatus() == ValidationResult.Status.PASS)
                .count();
        double passRate = total > 0 ? (double) passing / total : 1.0;
        metrics.put("validation_pass_rate", passRate);
        metrics.put("validator_count", engine.getValidatorCount());

        // Per-validator status
        Map<String, String> validatorStatuses = new LinkedHashMap<>();
        for (ValidationResult result : engine.getResults()) {
            validatorStatuses.put(result.getArea().name(), result.getStatus().name());
        }
        metrics.put("validator_statuses", validatorStatuses);

        // Backpressure queue metrics
        Map<String, Object> queueMetrics = new LinkedHashMap<>();
        queueMetrics.put("submitted", queue.getTotalSubmitted());
        queueMetrics.put("processed", queue.getTotalProcessed());
        queueMetrics.put("dropped", queue.getDroppedCount());
        queueMetrics.put("queue_size", queue.getQueueSize());
        queueMetrics.put("capacity", queue.getCapacity());
        queueMetrics.put("drop_policy", queue.getDropPolicy().name());
        queueMetrics.put("running", queue.isRunning());
        metrics.put("backpressure_queue", queueMetrics);

        // Feed connection metrics
        Map<String, Object> feedMetrics = new LinkedHashMap<>();
        feedMetrics.put("total_connections", feedManager.getConnectionCount());
        feedMetrics.put("active_connections", feedManager.getActiveConnectionCount());
        feedMetrics.put("stale_feeds", feedManager.checkHealth().size());
        metrics.put("feeds", feedMetrics);

        return metrics;
    }
}
