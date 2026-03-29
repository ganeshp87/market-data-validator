package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ThroughputValidatorTest {

    private ThroughputValidator validator;

    @BeforeEach
    void setUp() {
        // Use a small window for faster tests
        validator = new ThroughputValidator(10);
    }

    // --- Area ---

    @Test
    void areaIsThroughput() {
        assertThat(validator.getArea()).isEqualTo("THROUGHPUT");
    }

    // --- No ticks → PASS with zero throughput ---

    @Test
    void noTicksProducesPass() {
        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
        assertThat(result.getMessage()).contains("No ticks processed");
    }

    // --- Basic tick counting ---

    @Test
    void countsSingleTick() {
        feedTick("BTCUSDT", 1);
        assertThat(validator.getTotalTicks()).isEqualTo(1);
    }

    @Test
    void countsMultipleTicks() {
        for (int i = 1; i <= 100; i++) {
            feedTick("BTCUSDT", i);
        }
        assertThat(validator.getTotalTicks()).isEqualTo(100);
    }

    // --- Idempotency ---

    @Test
    void skipsDuplicateSequenceNumber() {
        feedTick("BTCUSDT", 5);
        feedTick("BTCUSDT", 5); // duplicate
        assertThat(validator.getTotalTicks()).isEqualTo(1);
    }

    @Test
    void skipsOlderSequenceNumber() {
        feedTick("BTCUSDT", 10);
        feedTick("BTCUSDT", 8); // older
        assertThat(validator.getTotalTicks()).isEqualTo(1);
    }

    @Test
    void tracksSequencePerSymbol() {
        feedTick("BTCUSDT", 5);
        feedTick("ETHUSDT", 5); // different symbol, same seqNum — should count
        assertThat(validator.getTotalTicks()).isEqualTo(2);
    }

    // --- tick() method — per-second snapshot ---

    @Test
    void tickSnapshotsCurrentCount() {
        feedTick("BTCUSDT", 1);
        feedTick("BTCUSDT", 2);
        feedTick("BTCUSDT", 3);

        validator.tick(); // Close the 1-second window

        assertThat(validator.getLastSecondRate()).isEqualTo(3);
    }

    @Test
    void tickResetsCurrentCountAfterSnapshot() {
        feedTick("BTCUSDT", 1);
        feedTick("BTCUSDT", 2);

        validator.tick(); // Snapshot: 2

        feedTick("BTCUSDT", 3); // New window

        validator.tick(); // Snapshot: 1

        assertThat(validator.getLastSecondRate()).isEqualTo(1);
    }

    @Test
    void tracksMaxThroughput() {
        // Window 1: 5 ticks
        for (int i = 1; i <= 5; i++) feedTick("BTCUSDT", i);
        validator.tick();

        // Window 2: 10 ticks
        for (int i = 6; i <= 15; i++) feedTick("BTCUSDT", i);
        validator.tick();

        // Window 3: 3 ticks
        for (int i = 16; i <= 18; i++) feedTick("BTCUSDT", i);
        validator.tick();

        assertThat(validator.getMaxThroughput()).isEqualTo(10);
    }

    // --- Rolling average ---

    @Test
    void calculatesRollingAverage() {
        // 3 windows: 10, 20, 30 → avg = 20
        for (int i = 1; i <= 10; i++) feedTick("BTCUSDT", i);
        validator.tick();

        for (int i = 11; i <= 30; i++) feedTick("BTCUSDT", i);
        validator.tick();

        for (int i = 31; i <= 60; i++) feedTick("BTCUSDT", i);
        validator.tick();

        assertThat(validator.getRollingAverage()).isEqualTo(20.0);
    }

    @Test
    void rollingAverageWrapsCircularBuffer() {
        // Fill 10-slot buffer, then overflow
        for (int sec = 0; sec < 12; sec++) {
            int tickCount = (sec + 1) * 2; // 2, 4, 6, ... 24
            int baseSeq = sec * 100;
            for (int t = 0; t < tickCount; t++) {
                feedTick("BTCUSDT", baseSeq + t + 1);
            }
            validator.tick();
        }

        // Buffer has slots 2(idx0)->10(idx2..9) overwritten by 11,12
        // Window should contain the last 10 values
        // Verify avg is computed from filled slots (10)
        assertThat(validator.getRollingAverage()).isGreaterThan(0);
    }

    // --- Drop detection ---

    @Test
    void noDropDetectedDuringWarmup() {
        // < 5 windows → no drop detection
        for (int i = 1; i <= 100; i++) feedTick("BTCUSDT", i);
        validator.tick();

        // Window 2: 0 ticks
        validator.tick();

        assertThat(validator.isDropDetected()).isFalse();
    }

    @Test
    void detectsDropAfterWarmup() {
        // Warm up with 5 windows of 100 ticks each
        int seq = 1;
        for (int w = 0; w < 5; w++) {
            for (int i = 0; i < 100; i++) {
                feedTick("BTCUSDT", seq++);
            }
            validator.tick();
        }
        // Rolling avg is 100. Now drop to 10 (< 50%)
        for (int i = 0; i < 10; i++) {
            feedTick("BTCUSDT", seq++);
        }
        validator.tick();

        assertThat(validator.isDropDetected()).isTrue();
    }

    @Test
    void noDropWhenRateAboveThreshold() {
        int seq = 1;
        for (int w = 0; w < 6; w++) {
            for (int i = 0; i < 100; i++) {
                feedTick("BTCUSDT", seq++);
            }
            validator.tick();
        }
        // Avg ≈ 100, current = 100 → no drop
        assertThat(validator.isDropDetected()).isFalse();
    }

    @Test
    void dropDetectionProducesWarnResult() {
        int seq = 1;
        for (int w = 0; w < 5; w++) {
            for (int i = 0; i < 100; i++) feedTick("BTCUSDT", seq++);
            validator.tick();
        }
        // Drop to 10
        for (int i = 0; i < 10; i++) feedTick("BTCUSDT", seq++);
        validator.tick();

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.WARN);
        assertThat(result.getMessage()).contains("dropped");
    }

    // --- Zero throughput / FAIL ---

    @Test
    void zeroThroughputFailAfterThreshold() {
        feedTick("BTCUSDT", 1);
        validator.tick();
        validator.setFeedConnected(true);

        // 5 consecutive zero-seconds
        for (int i = 0; i < 5; i++) {
            validator.tick(); // 0 ticks each second
        }

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.FAIL);
        assertThat(result.getMessage()).contains("Zero throughput");
    }

    @Test
    void zeroThroughputResetsOnNewTick() {
        feedTick("BTCUSDT", 1);
        validator.tick();
        validator.setFeedConnected(true);

        // 3 zero seconds (below threshold of 5)
        for (int i = 0; i < 3; i++) {
            validator.tick();
        }

        // New data arrives
        feedTick("BTCUSDT", 2);
        validator.tick();

        assertThat(validator.getConsecutiveZeroSeconds()).isEqualTo(0);
    }

    @Test
    void zeroThroughputOnlyWhenFeedConnected() {
        feedTick("BTCUSDT", 1);
        validator.tick();

        // Feed NOT connected — zero throughput should not count
        for (int i = 0; i < 10; i++) {
            validator.tick();
        }

        assertThat(validator.getConsecutiveZeroSeconds()).isEqualTo(0);
    }

    @Test
    void disconnectResetsFail() {
        feedTick("BTCUSDT", 1);
        validator.tick();
        validator.setFeedConnected(true);

        for (int i = 0; i < 5; i++) validator.tick(); // zero → FAIL

        // Feed disconnects
        validator.setFeedConnected(false);

        assertThat(validator.getConsecutiveZeroSeconds()).isEqualTo(0);
    }

    // --- PASS result ---

    @Test
    void normalThroughputProducesPass() {
        int seq = 1;
        for (int w = 0; w < 6; w++) {
            for (int i = 0; i < 100; i++) feedTick("BTCUSDT", seq++);
            validator.tick();
        }

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    // --- Details map ---

    @Test
    void resultDetailsContainsExpectedKeys() {
        feedTick("BTCUSDT", 1);
        validator.tick();

        Map<String, Object> details = validator.getResult().getDetails();
        assertThat(details).containsKeys(
                "messagesPerSecond", "rollingAverage", "maxThroughput",
                "totalTicks", "dropDetected", "consecutiveZeroSeconds", "windowSize"
        );
    }

    @Test
    void resultDetailsHasCorrectValues() {
        for (int i = 1; i <= 50; i++) feedTick("BTCUSDT", i);
        validator.tick();

        Map<String, Object> details = validator.getResult().getDetails();
        assertThat(details.get("messagesPerSecond")).isEqualTo(50L);
        assertThat(details.get("maxThroughput")).isEqualTo(50L);
        assertThat(details.get("totalTicks")).isEqualTo(50L);
        assertThat(details.get("dropDetected")).isEqualTo(false);
    }

    // --- Reset ---

    @Test
    void resetClearsAllState() {
        for (int i = 1; i <= 50; i++) feedTick("BTCUSDT", i);
        validator.tick();
        validator.setFeedConnected(true);

        validator.reset();

        assertThat(validator.getTotalTicks()).isEqualTo(0);
        assertThat(validator.getLastSecondRate()).isEqualTo(0);
        assertThat(validator.getMaxThroughput()).isEqualTo(0);
        assertThat(validator.getRollingAverage()).isEqualTo(0.0);
        assertThat(validator.isDropDetected()).isFalse();
        assertThat(validator.getConsecutiveZeroSeconds()).isEqualTo(0);
    }

    @Test
    void resetAllowsSameSequenceToBeProcessedAgain() {
        feedTick("BTCUSDT", 1);
        assertThat(validator.getTotalTicks()).isEqualTo(1);

        validator.reset();
        feedTick("BTCUSDT", 1); // Same seqNum — should be processed again after reset
        assertThat(validator.getTotalTicks()).isEqualTo(1);
    }

    // --- Configure ---

    @Test
    void configureDropPercent() {
        validator.configure(Map.of("dropPercent", 0.80));

        int seq = 1;
        for (int w = 0; w < 5; w++) {
            for (int i = 0; i < 100; i++) feedTick("BTCUSDT", seq++);
            validator.tick();
        }
        // 70 msg/s = 70% of avg (100) → with 80% threshold, this IS a drop
        for (int i = 0; i < 70; i++) feedTick("BTCUSDT", seq++);
        validator.tick();

        assertThat(validator.isDropDetected()).isTrue();
    }

    @Test
    void configureZeroThresholdSecs() {
        validator.configure(Map.of("zeroThresholdSecs", 2));

        feedTick("BTCUSDT", 1);
        validator.tick();
        validator.setFeedConnected(true);

        // Only 2 seconds of zero needed now
        validator.tick();
        validator.tick();

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.FAIL);
    }

    // --- Helpers ---

    private void feedTick(String symbol, long seqNum) {
        Tick tick = new Tick(symbol, new BigDecimal("45000.00"),
                new BigDecimal("1"), seqNum, Instant.now(), "test-feed");
        validator.onTick(tick);
    }
}
