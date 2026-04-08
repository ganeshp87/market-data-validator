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
        assertThat(validator.getMissingSequenceCount()).isEqualTo(0);
    }

    // --- Gap detection ---

    @Test
    void singleGapDetected() {
        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 3, BASE.plusSeconds(1)); // seq 2 missing

        assertThat(validator.getMissingSequenceCount()).isEqualTo(1);
    }

    @Test
    void multipleGapsInOneJumpCounted() {
        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 5, BASE.plusSeconds(1)); // seq 2,3,4 missing

        assertThat(validator.getMissingSequenceCount()).isEqualTo(3);
    }

    @Test
    void gapsCumulateAcrossMultipleJumps() {
        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 3, BASE.plusSeconds(1)); // 1 gap
        feedTick("BTCUSDT", 6, BASE.plusSeconds(2)); // 2 more gaps

        assertThat(validator.getMissingSequenceCount()).isEqualTo(3); // Total: 1 + 2
    }

    @Test
    void gapsTrackedPerSymbol() {
        feedTick("BTCUSDT", 1, BASE);
        feedTick("ETHUSDT", 1, BASE);
        feedTick("BTCUSDT", 3, BASE.plusSeconds(1)); // 1 gap for BTC
        feedTick("ETHUSDT", 2, BASE.plusSeconds(1)); // no gap for ETH

        assertThat(validator.getMissingSequenceCount()).isEqualTo(1);
    }

    @Test
    void firstTickForSymbolNeverGap() {
        feedTick("BTCUSDT", 100, BASE); // First tick — seq 100, no prior → no gap

        assertThat(validator.getMissingSequenceCount()).isEqualTo(0);
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
    void symbolRecoveredFromStaleAfterRecoveryWindow() {
        // heartbeat=5s, recovery=10s: each recovery tick is 2s apart (< heartbeat threshold)
        validator.configure(Map.of("heartbeatThresholdMs", 5000L, "staleRecoveryWindowMs", 10000L));

        feedTick("BTCUSDT", 1, BASE);
        feedTick("BTCUSDT", 2, BASE.plusSeconds(10)); // gap 10s > 5s → stale

        // Recovery ticks arrive every 2s (< 5s heartbeat) for 10+ seconds
        feedTick("BTCUSDT", 3, BASE.plusSeconds(12));  // recoveryStart; recovered = 0
        feedTick("BTCUSDT", 4, BASE.plusSeconds(14));  // recovered = 2s
        feedTick("BTCUSDT", 5, BASE.plusSeconds(16));  // recovered = 4s
        feedTick("BTCUSDT", 6, BASE.plusSeconds(18));  // recovered = 6s
        feedTick("BTCUSDT", 7, BASE.plusSeconds(20));  // recovered = 8s
        feedTick("BTCUSDT", 8, BASE.plusSeconds(23));  // recovered = 11s ≥ 10s → cleared

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
        assertThat(validator.getMissingSequenceCount()).isEqualTo(3);

        validator.reset();

        assertThat(validator.getTotalTicks()).isEqualTo(0);
        assertThat(validator.getMissingSequenceCount()).isEqualTo(0);
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
        assertThat(result.getDetails()).containsKey("gapEventCount");
        assertThat(result.getDetails()).containsKey("missingSequenceCount");
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
        assertThat(validator.getMissingSequenceCount()).isEqualTo(999);
    }

    @Test
    void recoveryFromStaleAfterNewTick() {
        // heartbeat=100ms, recovery=200ms: short windows for fast deterministic test
        validator.configure(Map.of("heartbeatThresholdMs", 100L, "staleRecoveryWindowMs", 200L));

        feedTick("BTCUSDT", 1, BASE);
        // Gap of 500ms > 100ms heartbeat → symbol goes stale
        feedTick("BTCUSDT", 2, BASE.plusMillis(500));

        assertThat(validator.getStaleSymbols())
                .as("symbol must be stale after gap exceeds heartbeat threshold")
                .contains("BTCUSDT");

        // Feed recovery ticks within heartbeat threshold spanning > 200ms recovery window
        feedTick("BTCUSDT", 3, BASE.plusMillis(550));  // recoveryStart
        feedTick("BTCUSDT", 4, BASE.plusMillis(600));   // 50ms into recovery
        feedTick("BTCUSDT", 5, BASE.plusMillis(700));   // 150ms into recovery
        feedTick("BTCUSDT", 6, BASE.plusMillis(800));   // 250ms ≥ 200ms → cleared

        assertThat(validator.getStaleSymbols())
                .as("symbol must recover after ticks span recovery window")
                .doesNotContain("BTCUSDT");
    }

    @Test
    void sequenceNumberOneForNewSymbolNeverCountsAsGap() {
        // First tick for any symbol should never be a gap, regardless of seq num
        feedTick("BTCUSDT", 100, BASE);

        assertThat(validator.getMissingSequenceCount()).isEqualTo(0);
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
        assertThat(validator.getMissingSequenceCount()).isEqualTo(0);
    }

    // --- Hysteresis: stale symbol requires 30s recovery window before clearing ---

    @Test
    void staleSymbolNotImmediatelyRecoveredAfterOneTick() {
        // heartbeat=5s, recovery=10s: same config as symbolRecoveredFromStaleAfterRecoveryWindow
        validator.configure(Map.of("heartbeatThresholdMs", 5000L, "staleRecoveryWindowMs", 10000L));

        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        feedTick("BTCUSDT", 1, base);
        feedTick("BTCUSDT", 2, base.plusSeconds(10)); // gap 10s > 5s → stale

        assertThat(validator.getStaleSymbols()).contains("BTCUSDT");

        // One recovery tick arrives within threshold — but recovery window (10s) has not elapsed
        feedTick("BTCUSDT", 3, base.plusSeconds(12));

        // Symbol must still be stale — one tick does not clear it
        assertThat(validator.getStaleSymbols())
                .as("symbol must remain stale until recovery window elapses")
                .contains("BTCUSDT");
    }

    @Test
    void staleSymbolRecoveredAfterRecoveryWindowElapsed() {
        // heartbeat=5s, recovery=10s: recovery ticks every 2s (< heartbeat) for > 10s
        validator.configure(Map.of("heartbeatThresholdMs", 5000L, "staleRecoveryWindowMs", 10000L));

        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        feedTick("BTCUSDT", 1, base);
        feedTick("BTCUSDT", 2, base.plusSeconds(10)); // goes stale

        assertThat(validator.getStaleSymbols()).contains("BTCUSDT");

        feedTick("BTCUSDT", 3, base.plusSeconds(12));  // recoveryStart; recovered = 0
        feedTick("BTCUSDT", 4, base.plusSeconds(14));  // recovered = 2s
        feedTick("BTCUSDT", 5, base.plusSeconds(16));  // recovered = 4s
        feedTick("BTCUSDT", 6, base.plusSeconds(18));  // recovered = 6s
        feedTick("BTCUSDT", 7, base.plusSeconds(20));  // recovered = 8s
        feedTick("BTCUSDT", 8, base.plusSeconds(23));  // recovered = 11s ≥ 10s → cleared

        assertThat(validator.getStaleSymbols())
                .as("symbol must be removed from staleSymbols after recovery window elapses")
                .doesNotContain("BTCUSDT");
    }

    // --- Helpers ---

    private void feedTick(String symbol, long seqNum, Instant receivedAt) {
        Tick tick = new Tick(symbol, new BigDecimal("45000"), new BigDecimal("1"),
                seqNum, receivedAt.minusMillis(50), "test-feed");
        tick.setReceivedTimestamp(receivedAt);
        validator.onTick(tick);
    }
}
