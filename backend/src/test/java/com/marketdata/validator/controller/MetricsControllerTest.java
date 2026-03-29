package com.marketdata.validator.controller;

import com.marketdata.validator.feed.FeedManager;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.validator.BackpressureQueue;
import com.marketdata.validator.validator.ValidatorEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MetricsControllerTest {

    private MetricsController controller;
    private ValidatorEngine engine;
    private BackpressureQueue queue;
    private FeedManager feedManager;

    @BeforeEach
    void setUp() {
        engine = mock(ValidatorEngine.class);
        queue = mock(BackpressureQueue.class);
        feedManager = mock(FeedManager.class);
        controller = new MetricsController(engine, queue, feedManager);
    }

    @Test
    void metricsReturnsTimestamp() {
        stubDefaults();
        Map<String, Object> metrics = controller.getMetrics();
        assertThat(metrics).containsKey("timestamp");
    }

    @Test
    void metricsReturnsTickCount() {
        stubDefaults();
        when(engine.getTickCount()).thenReturn(42L);

        Map<String, Object> metrics = controller.getMetrics();
        assertThat(metrics.get("tick_count_total")).isEqualTo(42L);
    }

    @Test
    void metricsReturnsValidationPassRate() {
        when(engine.getResults()).thenReturn(List.of(
                createResult("ACCURACY", ValidationResult.Status.PASS),
                createResult("LATENCY", ValidationResult.Status.PASS),
                createResult("ORDERING", ValidationResult.Status.FAIL)
        ));
        when(engine.getValidatorCount()).thenReturn(3);
        when(queue.getTotalSubmitted()).thenReturn(0L);
        when(queue.getTotalProcessed()).thenReturn(0L);
        when(queue.getDroppedCount()).thenReturn(0L);
        when(queue.getQueueSize()).thenReturn(0);
        when(queue.getCapacity()).thenReturn(10000);
        when(queue.getDropPolicy()).thenReturn(BackpressureQueue.DropPolicy.DROP_OLDEST);
        when(queue.isRunning()).thenReturn(true);
        when(feedManager.getConnectionCount()).thenReturn(0);
        when(feedManager.getActiveConnectionCount()).thenReturn(0L);
        when(feedManager.checkHealth()).thenReturn(List.of());

        Map<String, Object> metrics = controller.getMetrics();

        // 2 out of 3 pass = 0.666...
        double passRate = (Double) metrics.get("validation_pass_rate");
        assertThat(passRate).isBetween(0.66, 0.67);
    }

    @Test
    void metricsReturnsQueueMetrics() {
        stubDefaults();
        when(queue.getTotalSubmitted()).thenReturn(1000L);
        when(queue.getTotalProcessed()).thenReturn(990L);
        when(queue.getDroppedCount()).thenReturn(10L);
        when(queue.getQueueSize()).thenReturn(5);
        when(queue.getCapacity()).thenReturn(10000);

        Map<String, Object> metrics = controller.getMetrics();
        @SuppressWarnings("unchecked")
        Map<String, Object> queueMetrics = (Map<String, Object>) metrics.get("backpressure_queue");

        assertThat(queueMetrics.get("submitted")).isEqualTo(1000L);
        assertThat(queueMetrics.get("processed")).isEqualTo(990L);
        assertThat(queueMetrics.get("dropped")).isEqualTo(10L);
        assertThat(queueMetrics.get("queue_size")).isEqualTo(5);
    }

    @Test
    void metricsReturnsFeedMetrics() {
        stubDefaults();
        when(feedManager.getConnectionCount()).thenReturn(3);
        when(feedManager.getActiveConnectionCount()).thenReturn(2L);
        when(feedManager.checkHealth()).thenReturn(List.of("stale-1"));

        Map<String, Object> metrics = controller.getMetrics();
        @SuppressWarnings("unchecked")
        Map<String, Object> feedMetrics = (Map<String, Object>) metrics.get("feeds");

        assertThat(feedMetrics.get("total_connections")).isEqualTo(3);
        assertThat(feedMetrics.get("active_connections")).isEqualTo(2L);
        assertThat(feedMetrics.get("stale_feeds")).isEqualTo(1);
    }

    @Test
    void metricsReturnsValidatorStatuses() {
        when(engine.getResults()).thenReturn(List.of(
                createResult("ACCURACY", ValidationResult.Status.PASS),
                createResult("LATENCY", ValidationResult.Status.WARN)
        ));
        when(engine.getTickCount()).thenReturn(0L);
        when(engine.getValidatorCount()).thenReturn(2);
        stubQueueAndFeedDefaults();

        Map<String, Object> metrics = controller.getMetrics();
        @SuppressWarnings("unchecked")
        Map<String, String> statuses = (Map<String, String>) metrics.get("validator_statuses");

        assertThat(statuses).containsEntry("ACCURACY", "PASS");
        assertThat(statuses).containsEntry("LATENCY", "WARN");
    }

    @Test
    void passRateIsOneWhenNoValidators() {
        when(engine.getResults()).thenReturn(List.of());
        when(engine.getTickCount()).thenReturn(0L);
        when(engine.getValidatorCount()).thenReturn(0);
        stubQueueAndFeedDefaults();

        Map<String, Object> metrics = controller.getMetrics();
        assertThat(metrics.get("validation_pass_rate")).isEqualTo(1.0);
    }

    // --- Helpers ---

    private void stubDefaults() {
        when(engine.getResults()).thenReturn(List.of());
        when(engine.getTickCount()).thenReturn(0L);
        when(engine.getValidatorCount()).thenReturn(0);
        stubQueueAndFeedDefaults();
    }

    private void stubQueueAndFeedDefaults() {
        when(queue.getTotalSubmitted()).thenReturn(0L);
        when(queue.getTotalProcessed()).thenReturn(0L);
        when(queue.getDroppedCount()).thenReturn(0L);
        when(queue.getQueueSize()).thenReturn(0);
        when(queue.getCapacity()).thenReturn(10000);
        when(queue.getDropPolicy()).thenReturn(BackpressureQueue.DropPolicy.DROP_OLDEST);
        when(queue.isRunning()).thenReturn(true);
        when(feedManager.getConnectionCount()).thenReturn(0);
        when(feedManager.getActiveConnectionCount()).thenReturn(0L);
        when(feedManager.checkHealth()).thenReturn(List.of());
    }

    private ValidationResult createResult(String area, ValidationResult.Status status) {
        ValidationResult result = new ValidationResult(
                ValidationResult.Area.valueOf(area), status, "test", 0.0, 0.0);
        return result;
    }
}
