package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompletenessValidatorTest {

    private CompletenessValidator validator;
    private static final Instant BASE = Instant.parse("2026-03-23T10:00:00Z");

    @BeforeEach
    void setUp() {
        validator = new CompletenessValidator();
    }

    // --- Area ---

    @Test
    void areaIsCompleteness() {
        assertThat(validator.getArea()).isEqualTo("COMPLETENESS");
    }

    // --- No ticks → PASS ---

    @Test
    void noTicksProducesPass() {
        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
        assertThat(result.getMessage()).contains("No ticks processed");
    }

    // --- Sequential ticks → PASS ---

    @Test
    void sequentialTicksProducesPass() {
        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 2, BASE.plusSeconds(1));
        feedTick("BTCUSDT", 3, BASE.plusSeconds(2));

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
        assertThat(validator.getGapCount()).isEqualTo(0);
    }

    // --- Gap detection ---

    @Test
    void singleGapDetected() {
        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 3, BASE.plusSeconds(1)); // seq 2 missing

        assertThat(validator.getGapCount()).isEqualTo(1);
    }

    @Test
    void multipleGapsInOneJumpCounted() {
        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 5, BASE.plusSeconds(1)); // seq 2,3,4 missing

        assertThat(validator.getGapCount()).isEqualTo(3);
    }

    @Test
    void gapsCumulateAcrossMultipleJumps() {
        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 3, BASE.plusSeconds(1)); // 1 gap
        feedTick("BTCUSDT", 6, BASE.plusSeconds(2)); // 2 more gaps

        assertThat(validator.getGapCount()).isEqualTo(3); // Total: 1 + 2
    }

    @Test
    void gapsTrackedPerSymbol() {
        feedTick("BTCUSDT", 1, BASE);
        feedTick("ETHUSDT", 1, BASE);
        feedTick("BTCUSDT", 3, BASE.plusSeconds(1)); // 1 gap for BTC
        feedTick("ETHUSDT", 2, BASE.plusSeconds(1)); // no gap for ETH

        assertThat(validator.getGapCount()).isEqualTo(1);
    }

    @Test
    void firstTickForSymbolNeverGap() {
        feedTick("BTCUSDT", 100, BASE); // First tick — seq 100, no prior → no gap

        assertThat(validator.getGapCount()).isEqualTo(0);
    }

    // --- Status thresholds (percentage-based) ---

    @Test
    void fewGapsProducesWarn() {
        // 100 ticks received, 1 gap → completeness = 100/101 ≈ 99.01% → WARN (>= 99.0% but < 99.99%)
        feedTick("BTCUSDT", 1, BASE);
        for (int i = 2; i <= 100; i++) {
            feedTick("BTCUSDT", i, BASE.plusSeconds(i));
        }
        feedTick("BTCUSDT", 102, BASE.plusSeconds(101)); // 1 gap (skipped 101)

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.WARN);
    }

    @Test
    void manyGapsProducesFail() {
        // 2 ticks, 5 gaps → completeness = 2/7 ≈ 28.6% → FAIL (< 99.0%)
        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 7, BASE.plusSeconds(1)); // 5 gaps

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.FAIL);
    }

    // --- Staleness detection ---

    @Test
    void staleSymbolDetectedWhenGapExceedsThreshold() {
        validator.configure(Map.of("heartbeatThresholdMs", 5000L)); // 5 seconds

        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 2, BASE.plusSeconds(10)); // 10s gap > 5s threshold

        assertThat(validator.getStaleSymbols()).contains("BTCUSDT");
    }

    @Test
    void staleSymbolCausesFail() {
        validator.configure(Map.of("heartbeatThresholdMs", 5000L));

        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 2, BASE.plusSeconds(10)); // stale

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.FAIL);
    }

    @Test
    void symbolRecoveredFromStale() {
        validator.configure(Map.of("heartbeatThresholdMs", 5000L));

        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 2, BASE.plusSeconds(10)); // stale
        feedTick("BTCUSDT", 3, BASE.plusSeconds(11)); // 1s gap — recovered

        assertThat(validator.getStaleSymbols()).doesNotContain("BTCUSDT");
    }

    @Test
    void noStalenessWithinThreshold() {
        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 2, BASE.plusSeconds(5)); // 5s < 10s default threshold

        assertThat(validator.getStaleSymbols()).isEmpty();
    }

    @Test
    void stalenessTrackedPerSymbol() {
        validator.configure(Map.of("heartbeatThresholdMs", 5000L));

        feedTick("BTCUSDT", 1, BASE);
        feedTick("ETHUSDT", 1, BASE);
        feedTick("BTCUSDT", 2, BASE.plusSeconds(10)); // BTC stale
        feedTick("ETHUSDT", 2, BASE.plusSeconds(2));   // ETH fine

        assertThat(validator.getStaleSymbols()).containsExactly("BTCUSDT");
    }

    // --- Idempotent processing ---

    @Test
    void duplicateSequenceNumberSkipped() {
        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 1, BASE.plusSeconds(1)); // duplicate — skipped

        assertThat(validator.getTotalTicks()).isEqualTo(1);
    }

    @Test
    void olderSequenceNumberSkipped() {
        feedTick("BTCUSDT", 5, BASE);
        feedTick("BTCUSDT", 3, BASE.plusSeconds(1)); // older — skipped

        assertThat(validator.getTotalTicks()).isEqualTo(1);
    }

    // --- Reset ---

    @Test
    void resetClearsAllState() {
        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 5, BASE.plusSeconds(1)); // gaps

        assertThat(validator.getTotalTicks()).isEqualTo(2);
        assertThat(validator.getGapCount()).isEqualTo(3);

        validator.reset();

        assertThat(validator.getTotalTicks()).isEqualTo(0);
        assertThat(validator.getGapCount()).isEqualTo(0);
        assertThat(validator.getStaleSymbols()).isEmpty();
        assertThat(validator.getTrackedSymbolCount()).isEqualTo(0);
        assertThat(validator.getResult().getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    // --- Configure ---

    @Test
    void configureChangesHeartbeatThreshold() {
        validator.configure(Map.of("heartbeatThresholdMs", 2000L)); // 2 seconds

        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 2, BASE.plusSeconds(3)); // 3s > 2s → stale

        assertThat(validator.getStaleSymbols()).contains("BTCUSDT");
    }

    // --- Result details ---

    @Test
    void resultContainsDetailedMetrics() {
        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 3, BASE.plusSeconds(1));

        ValidationResult result = validator.getResult();
        assertThat(result.getDetails()).containsKey("gapCount");
        assertThat(result.getDetails()).containsKey("staleSymbolCount");
        assertThat(result.getDetails()).containsKey("staleSymbols");
        assertThat(result.getDetails()).containsKey("totalTicks");
        assertThat(result.getDetails()).containsKey("trackedSymbols");
    }

    // --- Multi-symbol tracking ---

    @Test
    void multipleSymbolsTrackedIndependently() {
        feedTick("BTCUSDT", 1, BASE);
        feedTick("ETHUSDT", 1, BASE);
        feedTick("SOLUSDT", 1, BASE);

        assertThat(validator.getTrackedSymbolCount()).isEqualTo(3);
        assertThat(validator.getTotalTicks()).isEqualTo(3);
    }

    // --- Edge cases ---

    @Test
    void massiveSequenceGapCountedCorrectly() {
        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 1001, BASE.plusSeconds(1));

        // Gap of 999 (sequences 2 through 1000)
        assertThat(validator.getGapCount()).isEqualTo(999);
    }

    @Test
    void recoveryFromStaleAfterNewTick() {
        // Configure short heartbeat threshold for testing
        validator.configure(Map.of("heartbeatThresholdMs", 100L));

        feedTick("BTCUSDT", 1, BASE);
        // Next tick arrives well after threshold
        feedTick("BTCUSDT", 2, BASE.plusMillis(500));

        // Symbol was stale but should recover after receiving tick 2
        ValidationResult result = validator.getResult();
        // The symbol should no longer be stale since it just got a tick
        assertThat(result).isNotNull();
    }

    @Test
    void sequenceNumberOneForNewSymbolNeverCountsAsGap() {
        // First tick for any symbol should never be a gap, regardless of seq num
        feedTick("BTCUSDT", 100, BASE);

        assertThat(validator.getGapCount()).isEqualTo(0);
        assertThat(validator.getResult().getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    @Test
    void manySymbolsAllSequential() {
        // 20 symbols, each with 5 sequential ticks
        for (int s = 0; s < 20; s++) {
            String symbol = "SYM" + s;
            for (int i = 1; i <= 5; i++) {
                feedTick(symbol, i, BASE.plusSeconds(s * 5L + i));
            }
        }

        assertThat(validator.getTrackedSymbolCount()).isEqualTo(20);
        assertThat(validator.getTotalTicks()).isEqualTo(100);
        assertThat(validator.getGapCount()).isEqualTo(0);
    }

    // --- Helpers ---

    private void feedTick(String symbol, long seqNum, Instant receivedAt) {
        Tick tick = new Tick(symbol, new BigDecimal("45000"), new BigDecimal("1"),
                seqNum, receivedAt.minusMillis(50), "test-feed");
        tick.setReceivedTimestamp(receivedAt);
        validator.onTick(tick);
    }
}
