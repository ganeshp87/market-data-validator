package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyValidatorTest {

    private LatencyValidator validator;

    @BeforeEach
    void setUp() {
        validator = new LatencyValidator();
    }

    // --- Area ---

    @Test
    void areaIsLatency() {
        assertThat(validator.getArea()).isEqualTo("LATENCY");
    }

    // --- No ticks → PASS ---

    @Test
    void noTicksProducesPass() {
        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
        assertThat(result.getMessage()).contains("No ticks processed");
    }

    // --- Basic latency tracking ---

    @Test
    void singleTickRecordsLatency() {
        feedTickWithLatency("BTCUSDT", 100, 1);

        assertThat(validator.getTotalTicks()).isEqualTo(1);
        assertThat(validator.getBufferCount()).isEqualTo(1);
        assertThat(validator.getMinLatency()).isEqualTo(100);
        assertThat(validator.getMaxLatency()).isEqualTo(100);
    }

    @Test
    void multipleTicksTrackMinMax() {
        feedTickWithLatency("BTCUSDT", 50, 1);
        feedTickWithLatency("BTCUSDT", 200, 2);
        feedTickWithLatency("BTCUSDT", 100, 3);

        assertThat(validator.getMinLatency()).isEqualTo(50);
        assertThat(validator.getMaxLatency()).isEqualTo(200);
    }

    // --- Percentile calculation ---

    @Test
    void percentilesCalculatedCorrectly() {
        // Feed 100 ticks with latencies 1..100ms
        for (int i = 1; i <= 100; i++) {
            feedTickWithLatency("BTCUSDT", i, i);
        }

        assertThat(validator.getP50()).isEqualTo(50);
        assertThat(validator.getP95()).isEqualTo(95);
        assertThat(validator.getP99()).isEqualTo(99);
    }

    @Test
    void percentilesWithUniformLatency() {
        // All ticks have same latency
        for (int i = 1; i <= 50; i++) {
            feedTickWithLatency("BTCUSDT", 42, i);
        }

        assertThat(validator.getP50()).isEqualTo(42);
        assertThat(validator.getP95()).isEqualTo(42);
        assertThat(validator.getP99()).isEqualTo(42);
    }

    // --- Circular buffer bounded memory ---

    @Test
    void circularBufferBoundsMemory() {
        // Use small buffer for testing
        validator.configure(Map.of("bufferSize", 10));

        // Feed more ticks than buffer size
        for (int i = 1; i <= 20; i++) {
            feedTickWithLatency("BTCUSDT", i * 10, i);
        }

        // Buffer should contain only last 10 values (110..200)
        assertThat(validator.getBufferCount()).isEqualTo(10);
        assertThat(validator.getTotalTicks()).isEqualTo(20);
    }

    @Test
    void circularBufferOverwritesOldValues() {
        validator.configure(Map.of("bufferSize", 5));

        // Feed 5 low-latency ticks
        for (int i = 1; i <= 5; i++) {
            feedTickWithLatency("BTCUSDT", 10, i);
        }
        assertThat(validator.getP95()).isEqualTo(10);

        // Now overwrite with 5 high-latency ticks
        for (int i = 6; i <= 10; i++) {
            feedTickWithLatency("BTCUSDT", 500, i);
        }
        // Buffer now contains only 500ms values
        assertThat(validator.getP95()).isEqualTo(500);
    }

    // --- Status thresholds ---

    @Test
    void lowLatencyProducesPass() {
        // p95 < 100ms (default threshold), no spikes
        for (int i = 1; i <= 100; i++) {
            feedTickWithLatency("BTCUSDT", 50, i); // 50ms — well under threshold
        }

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    @Test
    void highP95ProducesWarn() {
        // p95 >= 100ms but < 200ms → WARN
        for (int i = 1; i <= 100; i++) {
            feedTickWithLatency("BTCUSDT", 150, i); // All 150ms → p95=150ms
        }

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.WARN);
    }

    @Test
    void veryHighP95ProducesFail() {
        // p95 >= threshold * 2 (1000ms) → FAIL
        for (int i = 1; i <= 100; i++) {
            feedTickWithLatency("BTCUSDT", 1100, i);
        }

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.FAIL);
    }

    // --- Spike detection ---

    @Test
    void spikeDetectedWhenLatencyExceedsThreeTimesP99() {
        // Create baseline: 100 ticks at 100ms → p99 ≈ 100ms
        for (int i = 1; i <= 100; i++) {
            feedTickWithLatency("BTCUSDT", 100, i);
        }

        // Spike: 400ms > 3 × 100ms p99
        feedTickWithLatency("BTCUSDT", 400, 101);

        assertThat(validator.getSpikeCount()).isEqualTo(1);
    }

    @Test
    void noSpikeWhenLatencyBelowThreshold() {
        // Baseline: 100 ticks at 100ms
        for (int i = 1; i <= 100; i++) {
            feedTickWithLatency("BTCUSDT", 100, i);
        }

        // 250ms < 3 × 100ms — not a spike
        feedTickWithLatency("BTCUSDT", 250, 101);

        assertThat(validator.getSpikeCount()).isEqualTo(0);
    }

    @Test
    void spikeDetectionRequiresMinimumSamples() {
        // With < 100 samples, no spike detection occurs
        feedTickWithLatency("BTCUSDT", 10, 1);
        feedTickWithLatency("BTCUSDT", 10000, 2); // Huge latency but too few samples

        assertThat(validator.getSpikeCount()).isEqualTo(0); // No spike — not enough samples
    }

    @Test
    void manySpikesCauseFail() {
        // Use buffer=2000 so 20 spike values stay < 1% of buffer (keeps p99 at baseline)
        validator.configure(Map.of("bufferSize", 2000));

        // Baseline: 2000 ticks at 100ms → fills buffer, p99 = 100ms
        for (int i = 1; i <= 2000; i++) {
            feedTickWithLatency("BTCUSDT", 100, i);
        }

        // 20 spikes at 10000ms → each exceeds 3 × p99(100ms) = 300ms → spike detected
        for (int i = 2001; i <= 2020; i++) {
            feedTickWithLatency("BTCUSDT", 10000, i);
        }

        assertThat(validator.getSpikeCount()).isGreaterThanOrEqualTo(20);
        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.FAIL);
    }

    // --- Idempotent processing ---

    @Test
    void duplicateSequenceNumberSkipped() {
        feedTickWithLatency("BTCUSDT", 100, 1);
        feedTickWithLatency("BTCUSDT", 200, 1); // duplicate — skipped

        assertThat(validator.getTotalTicks()).isEqualTo(1);
        assertThat(validator.getBufferCount()).isEqualTo(1);
    }

    @Test
    void olderSequenceNumberSkipped() {
        feedTickWithLatency("BTCUSDT", 100, 5);
        feedTickWithLatency("BTCUSDT", 200, 3); // older — skipped

        assertThat(validator.getTotalTicks()).isEqualTo(1);
    }

    // --- Per-symbol idempotency ---

    @Test
    void idempotencyTrackedPerSymbol() {
        feedTickWithLatency("BTCUSDT", 100, 1);
        feedTickWithLatency("ETHUSDT", 200, 1); // Same seq, different symbol — NOT skipped

        assertThat(validator.getTotalTicks()).isEqualTo(2);
    }

    // --- Reset ---

    @Test
    void resetClearsAllState() {
        for (int i = 1; i <= 10; i++) {
            feedTickWithLatency("BTCUSDT", 100, i);
        }

        assertThat(validator.getTotalTicks()).isEqualTo(10);

        validator.reset();

        assertThat(validator.getTotalTicks()).isEqualTo(0);
        assertThat(validator.getSpikeCount()).isEqualTo(0);
        assertThat(validator.getBufferCount()).isEqualTo(0);
        assertThat(validator.getResult().getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    // --- Configure ---

    @Test
    void configureChangesThreshold() {
        validator.configure(Map.of("thresholdMs", 200L));

        // All ticks at 300ms → p95=300ms >= 200ms threshold → WARN
        for (int i = 1; i <= 100; i++) {
            feedTickWithLatency("BTCUSDT", 300, i);
        }

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.WARN);
    }

    // --- Result details ---

    @Test
    void resultContainsDetailedMetrics() {
        feedTickWithLatency("BTCUSDT", 100, 1);

        ValidationResult result = validator.getResult();
        assertThat(result.getDetails()).containsKey("p50");
        assertThat(result.getDetails()).containsKey("p95");
        assertThat(result.getDetails()).containsKey("p99");
        assertThat(result.getDetails()).containsKey("min");
        assertThat(result.getDetails()).containsKey("max");
        assertThat(result.getDetails()).containsKey("spikeCount");
        assertThat(result.getDetails()).containsKey("totalTicks");
        assertThat(result.getDetails()).containsKey("bufferSamples");
    }

    // --- Result metric is p95 ---

    @Test
    void resultMetricIsP95() {
        for (int i = 1; i <= 100; i++) {
            feedTickWithLatency("BTCUSDT", 42, i);
        }

        ValidationResult result = validator.getResult();
        assertThat(result.getMetric()).isEqualTo(42.0);
    }

    // --- Negative latency / clock skew ---

    @Test
    void negativeLatencyClampsToZeroInBuffer() {
        // Simulate clock skew: exchange timestamp ahead of received timestamp
        Instant exchangeTs = Instant.parse("2026-03-23T10:00:02.000Z");
        Instant receivedTs = Instant.parse("2026-03-23T10:00:00.000Z"); // 2s behind

        Tick tick = new Tick("BTCUSDT", new BigDecimal("45000"), new BigDecimal("1"),
                1, exchangeTs, "test-feed");
        tick.setReceivedTimestamp(receivedTs);

        validator.onTick(tick);

        // Negative latency should be clamped to 0, not stored as -2000
        assertThat(validator.getMinLatency()).isEqualTo(0);
        assertThat(validator.getMaxLatency()).isEqualTo(0);
        assertThat(validator.getP50()).isEqualTo(0);
    }

    @Test
    void clockSkewTicksDoNotCauseFailure() {
        // Feed 100 ticks with clock skew (negative raw latency)
        for (int i = 1; i <= 100; i++) {
            Instant exchangeTs = Instant.parse("2026-03-23T10:00:02.000Z");
            Instant receivedTs = Instant.parse("2026-03-23T10:00:00.000Z");

            Tick tick = new Tick("BTCUSDT", new BigDecimal("45000"), new BigDecimal("1"),
                    i, exchangeTs, "test-feed");
            tick.setReceivedTimestamp(receivedTs);
            validator.onTick(tick);
        }

        // All clamped to 0ms — should PASS, not FAIL
        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
        assertThat(result.getMetric()).isEqualTo(0.0); // p95 = 0
    }

    @Test
    void mixOfNormalAndClockSkewTicksHandledCorrectly() {
        // 50 normal ticks at 50ms
        for (int i = 1; i <= 50; i++) {
            feedTickWithLatency("BTCUSDT", 50, i);
        }

        // 50 clock-skew ticks (negative raw latency → clamped to 0)
        for (int i = 51; i <= 100; i++) {
            Instant exchangeTs = Instant.parse("2026-03-23T10:00:01.500Z");
            Instant receivedTs = Instant.parse("2026-03-23T10:00:00.000Z");

            Tick tick = new Tick("BTCUSDT", new BigDecimal("45000"), new BigDecimal("1"),
                    i, exchangeTs, "test-feed");
            tick.setReceivedTimestamp(receivedTs);
            validator.onTick(tick);
        }

        // p95 should be 50ms (from normal ticks), min should be 0 (from clamped), not -1500
        assertThat(validator.getMinLatency()).isEqualTo(0);
        assertThat(validator.getMaxLatency()).isEqualTo(50);

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    // --- Helpers ---

    private void feedTickWithLatency(String symbol, long latencyMs, long seqNum) {
        Instant exchangeTs = Instant.parse("2026-03-23T10:00:00Z");
        Instant receivedTs = exchangeTs.plusMillis(latencyMs);

        Tick tick = new Tick(symbol, new BigDecimal("45000"), new BigDecimal("1"),
                seqNum, exchangeTs, "test-feed");
        tick.setReceivedTimestamp(receivedTs);

        validator.onTick(tick);
    }
}
