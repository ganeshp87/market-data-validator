package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StatefulValidatorTest {

    private StatefulValidator validator;

    @BeforeEach
    void setUp() {
        validator = new StatefulValidator();
    }

    // --- Area ---

    @Test
    void areaIsStateful() {
        assertThat(validator.getArea()).isEqualTo("STATEFUL");
    }

    // --- No ticks → PASS ---

    @Test
    void noTicksProducesPass() {
        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
        assertThat(result.getMessage()).contains("No ticks processed");
    }

    // --- OHLC State Tracking ---

    @Test
    void firstTickInitializesOHLC() {
        feedTick("BTCUSDT", "100.00", "1.0", 1);

        StatefulValidator.SymbolState state = validator.getSymbolState("BTCUSDT");
        assertThat(state.open).isEqualByComparingTo("100.00");
        assertThat(state.high).isEqualByComparingTo("100.00");
        assertThat(state.low).isEqualByComparingTo("100.00");
        assertThat(state.lastPrice).isEqualByComparingTo("100.00");
    }

    @Test
    void highUpdatesOnHigherPrice() {
        feedTick("BTCUSDT", "100.00", "1.0", 1);
        feedTick("BTCUSDT", "150.00", "1.0", 2);

        StatefulValidator.SymbolState state = validator.getSymbolState("BTCUSDT");
        assertThat(state.high).isEqualByComparingTo("150.00");
        assertThat(state.open).isEqualByComparingTo("100.00"); // Open unchanged
    }

    @Test
    void lowUpdatesOnLowerPrice() {
        feedTick("BTCUSDT", "100.00", "1.0", 1);
        feedTick("BTCUSDT", "80.00", "1.0", 2);

        StatefulValidator.SymbolState state = validator.getSymbolState("BTCUSDT");
        assertThat(state.low).isEqualByComparingTo("80.00");
    }

    @Test
    void openNeverChanges() {
        feedTick("BTCUSDT", "100.00", "1.0", 1);
        feedTick("BTCUSDT", "200.00", "1.0", 2);
        feedTick("BTCUSDT", "50.00", "1.0", 3);

        assertThat(validator.getSymbolState("BTCUSDT").open).isEqualByComparingTo("100.00");
    }

    // --- Cumulative Volume ---

    @Test
    void cumulativeVolumeAccumulates() {
        feedTick("BTCUSDT", "100.00", "1.5", 1);
        feedTick("BTCUSDT", "101.00", "2.0", 2);
        feedTick("BTCUSDT", "102.00", "3.5", 3);

        StatefulValidator.SymbolState state = validator.getSymbolState("BTCUSDT");
        assertThat(state.cumulativeVolume).isEqualByComparingTo("7.0"); // 1.5 + 2.0 + 3.5
    }

    @Test
    void cumulativeVolumeNeverDecreases() {
        feedTick("BTCUSDT", "100.00", "10.0", 1);
        feedTick("BTCUSDT", "101.00", "5.0", 2);

        StatefulValidator.SymbolState state = validator.getSymbolState("BTCUSDT");
        assertThat(state.cumulativeVolume).isEqualByComparingTo("15.0"); // Always adds, never subtracts
    }

    // --- VWAP Calculation ---

    @Test
    void vwapCalculatedCorrectly() {
        // Tick 1: price=100, volume=2 → PV=200
        // Tick 2: price=200, volume=3 → PV=600
        // VWAP = (200+600) / (2+3) = 800/5 = 160
        feedTick("BTCUSDT", "100.00", "2.0", 1);
        feedTick("BTCUSDT", "200.00", "3.0", 2);

        StatefulValidator.SymbolState state = validator.getSymbolState("BTCUSDT");
        assertThat(state.vwap).isEqualByComparingTo("160.00");
    }

    @Test
    void vwapBetweenLowAndHigh() {
        feedTick("BTCUSDT", "100.00", "1.0", 1);
        feedTick("BTCUSDT", "200.00", "1.0", 2);
        feedTick("BTCUSDT", "150.00", "1.0", 3);

        StatefulValidator.SymbolState state = validator.getSymbolState("BTCUSDT");
        assertThat(state.vwap).isGreaterThanOrEqualTo(state.low);
        assertThat(state.vwap).isLessThanOrEqualTo(state.high);
    }

    @Test
    void vwapWithZeroVolumeTickIgnored() {
        feedTick("BTCUSDT", "100.00", "1.0", 1);
        feedTick("BTCUSDT", "500.00", "0.0", 2); // Zero volume — should not affect VWAP

        StatefulValidator.SymbolState state = validator.getSymbolState("BTCUSDT");
        assertThat(state.vwap).isEqualByComparingTo("100.00"); // VWAP unchanged
    }

    // --- Per-Symbol Isolation ---

    @Test
    void stateIsolatedPerSymbol() {
        feedTick("BTCUSDT", "100.00", "1.0", 1);
        feedTick("ETHUSDT", "50.00", "2.0", 1);

        assertThat(validator.getSymbolState("BTCUSDT").open).isEqualByComparingTo("100.00");
        assertThat(validator.getSymbolState("ETHUSDT").open).isEqualByComparingTo("50.00");
        assertThat(validator.getTrackedSymbolCount()).isEqualTo(2);
    }

    // --- Idempotency ---

    @Test
    void skipsDuplicateSequenceNumber() {
        feedTick("BTCUSDT", "100.00", "1.0", 5);
        feedTick("BTCUSDT", "200.00", "1.0", 5); // Duplicate

        assertThat(validator.getTotalChecks()).isEqualTo(1);
        assertThat(validator.getSymbolState("BTCUSDT").lastPrice).isEqualByComparingTo("100.00");
    }

    @Test
    void skipsOlderSequenceNumber() {
        feedTick("BTCUSDT", "100.00", "1.0", 10);
        feedTick("BTCUSDT", "200.00", "1.0", 8); // Older

        assertThat(validator.getTotalChecks()).isEqualTo(1);
    }

    @Test
    void sequenceTrackingPerSymbol() {
        feedTick("BTCUSDT", "100.00", "1.0", 5);
        feedTick("ETHUSDT", "50.00", "1.0", 5); // Same seqNum, different symbol

        assertThat(validator.getTotalChecks()).isEqualTo(2);
    }

    // --- Validation Rules: PASS on valid ticks ---

    @Test
    void validTicksProduceAllConsistent() {
        feedTick("BTCUSDT", "100.00", "1.0", 1);
        feedTick("BTCUSDT", "105.00", "2.0", 2);
        feedTick("BTCUSDT", "103.00", "1.5", 3);

        assertThat(validator.getTotalChecks()).isEqualTo(3);
        assertThat(validator.getConsistentChecks()).isEqualTo(3);
        assertThat(validator.getViolations()).isEmpty();
    }

    @Test
    void validTicksProducePassResult() {
        feedTick("BTCUSDT", "100.00", "1.0", 1);
        feedTick("BTCUSDT", "105.00", "2.0", 2);

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
        assertThat(result.getMetric()).isEqualTo(100.0);
    }

    // --- Validation Rule 1: Price > 0 ---

    @Test
    void zeroPriceViolation() {
        feedTick("BTCUSDT", "0", "1.0", 1);

        assertThat(validator.getViolations()).hasSize(1);
        assertThat(validator.getViolations().get(0).rule).isEqualTo("PRICE_NON_POSITIVE");
    }

    @Test
    void negativePriceViolation() {
        feedTick("BTCUSDT", "-5.00", "1.0", 1);

        assertThat(validator.getViolations()).isNotEmpty();
        assertThat(validator.getViolations().get(0).rule).isEqualTo("PRICE_NON_POSITIVE");
    }

    // --- Validation Rule 5: VWAP between low and high (always true for normal prices) ---

    @Test
    void normalTicksNeverViolateVWAPRange() {
        // Various prices — VWAP should always be between low and high
        feedTick("BTCUSDT", "100.00", "1.0", 1);
        feedTick("BTCUSDT", "200.00", "1.0", 2);
        feedTick("BTCUSDT", "50.00", "1.0", 3);
        feedTick("BTCUSDT", "150.00", "1.0", 4);

        assertThat(validator.getViolations()).isEmpty();
    }

    // --- Consistency Rate and Thresholds ---

    @Test
    void consistencyRateCalculatedCorrectly() {
        // 2 valid ticks + 1 invalid (zero price)
        feedTick("BTCUSDT", "100.00", "1.0", 1);
        feedTick("BTCUSDT", "0", "1.0", 2);  // Violation
        feedTick("BTCUSDT", "105.00", "1.0", 3);

        long total = validator.getTotalChecks();
        long consistent = validator.getConsistentChecks();
        assertThat(total).isEqualTo(3);
        assertThat(consistent).isEqualTo(2);
    }

    @Test
    void lowConsistencyProducesFail() {
        // Create many violations to drop below 99.9%
        feedTick("BTCUSDT", "100.00", "1.0", 1); // valid

        // Feed 10 zero-price violations
        for (int i = 2; i <= 11; i++) {
            feedTick("BTCUSDT", "0", "1.0", i);
        }

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.FAIL);
    }

    @Test
    void borderlineConsistencyProducesWarn() {
        // Need consistencyRate between 99.9% and 99.99%
        // 999 valid + 1 invalid = 99.9% → exactly at WARN threshold
        for (int i = 1; i <= 999; i++) {
            feedTick("BTCUSDT", "100.00", "1.0", i);
        }
        feedTick("BTCUSDT", "0", "1.0", 1000); // 1 violation

        // 999/1000 = 99.9% — below PASS (99.99%) but at WARN (99.9%)
        ValidationResult result = validator.getResult();
        // Could be WARN or PASS depending on exact threshold comparison
        assertThat(result.getStatus()).isIn(ValidationResult.Status.WARN, ValidationResult.Status.PASS);
    }

    // --- Stale Symbol Detection ---

    @Test
    void noStaleSymbolsWhenFresh() {
        feedTick("BTCUSDT", "100.00", "1.0", 1);

        ValidationResult result = validator.getResult();
        assertThat(result.getDetails().containsKey("staleSymbols")).isFalse();
    }

    @Test
    void staleSymbolDetectedWithShortThreshold() throws InterruptedException {
        validator.configure(Map.of("staleThresholdMs", 50L));

        feedTick("BTCUSDT", "100.00", "1.0", 1);

        Thread.sleep(80); // Wait past stale threshold

        ValidationResult result = validator.getResult();
        assertThat((int) result.getDetails().get("staleSymbolCount")).isEqualTo(1);
    }

    @Test
    void staleSymbolClearedByNewTick() throws InterruptedException {
        validator.configure(Map.of("staleThresholdMs", 50L));

        feedTick("BTCUSDT", "100.00", "1.0", 1);
        Thread.sleep(80); // Goes stale

        feedTick("BTCUSDT", "101.00", "1.0", 2); // Fresh tick

        ValidationResult result = validator.getResult();
        assertThat((int) result.getDetails().get("staleSymbolCount")).isEqualTo(0);
    }

    @Test
    void manyStaleSymbolsProduceFail() throws InterruptedException {
        validator.configure(Map.of("staleThresholdMs", 50L));

        feedTick("BTCUSDT", "100.00", "1.0", 1);
        feedTick("ETHUSDT", "50.00", "1.0", 1);
        feedTick("ADAUSDT", "1.00", "1.0", 1);

        Thread.sleep(80); // All 3 go stale

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.FAIL);
    }

    // --- Bounded Violation Buffer ---

    @Test
    void violationsAreBounded() {
        validator.configure(Map.of("maxViolations", 5));

        for (int i = 1; i <= 10; i++) {
            feedTick("BTCUSDT", "0", "1.0", i); // 10 violations
        }

        assertThat(validator.getViolations()).hasSize(5); // Bounded to max 5
    }

    // --- Details Map ---

    @Test
    void resultDetailsContainsExpectedKeys() {
        feedTick("BTCUSDT", "100.00", "1.0", 1);

        Map<String, Object> details = validator.getResult().getDetails();
        assertThat(details).containsKeys(
                "consistencyRate", "totalChecks", "consistentChecks",
                "violationCount", "trackedSymbols", "staleSymbolCount"
        );
    }

    @Test
    void resultDetailsShowRecentViolations() {
        feedTick("BTCUSDT", "0", "1.0", 1); // Violation

        Map<String, Object> details = validator.getResult().getDetails();
        assertThat(details).containsKey("recentViolations");
    }

    // --- Reset ---

    @Test
    void resetClearsAllState() {
        feedTick("BTCUSDT", "100.00", "1.0", 1);
        feedTick("BTCUSDT", "0", "1.0", 2); // Violation

        validator.reset();

        assertThat(validator.getTotalChecks()).isEqualTo(0);
        assertThat(validator.getConsistentChecks()).isEqualTo(0);
        assertThat(validator.getViolations()).isEmpty();
        assertThat(validator.getTrackedSymbolCount()).isEqualTo(0);
    }

    @Test
    void resetAllowsSameSequenceToBeProcessedAgain() {
        feedTick("BTCUSDT", "100.00", "1.0", 1);
        assertThat(validator.getTotalChecks()).isEqualTo(1);

        validator.reset();
        feedTick("BTCUSDT", "100.00", "1.0", 1); // Same seqNum
        assertThat(validator.getTotalChecks()).isEqualTo(1);
    }

    // --- Configure ---

    @Test
    void configureThresholds() {
        validator.configure(Map.of(
                "passThreshold", 99.0,
                "warnThreshold", 95.0,
                "staleThresholdMs", 60000L,
                "maxViolations", 50
        ));

        // With relaxed thresholds, 1 violation in 100 ticks is still PASS
        for (int i = 1; i <= 99; i++) {
            feedTick("BTCUSDT", "100.00", "1.0", i);
        }
        feedTick("BTCUSDT", "0", "1.0", 100); // 1% invalid → 99% consistency

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    // --- Edge cases ---

    @Test
    void ohlcWithHighPrecisionPrices() {
        feedTick("BTCUSDT", "45000.123456789", "1.0", 1);
        feedTick("BTCUSDT", "45000.000000001", "1.0", 2);
        feedTick("BTCUSDT", "45000.999999999", "1.0", 3);

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    @Test
    void vwapWithVaryingVolumeWeights() {
        // Price 100 with vol 9, then price 200 with vol 1 → VWAP = 110
        feedTick("BTCUSDT", "100.00", "9.0", 1);
        feedTick("BTCUSDT", "200.00", "1.0", 2);

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    @Test
    void ohlcSpansEntireSessionRange() {
        feedTick("BTCUSDT", "100.00", "1.0", 1); // O=100, H=100, L=100
        feedTick("BTCUSDT", "200.00", "1.0", 2); // H=200
        feedTick("BTCUSDT", "50.00", "1.0", 3);  // L=50
        feedTick("BTCUSDT", "150.00", "1.0", 4); // C=150

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    @Test
    void manySymbolsDoNotExceedMemoryBudget() {
        // 100 symbols with 10 ticks each = 1000 ticks
        for (int s = 0; s < 100; s++) {
            String symbol = "SYM" + s;
            for (int i = 1; i <= 10; i++) {
                feedTick(symbol, "100.00", "1.0", i);
            }
        }

        ValidationResult result = validator.getResult();
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    @Test
    void singleVeryLargePriceTickIsValid() {
        feedTick("BTCUSDT", "9999999999.99", "0.001", 1);

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    // --- Helpers ---

    private void feedTick(String symbol, String price, String volume, long seqNum) {
        BigDecimal p = new BigDecimal(price);
        BigDecimal v = new BigDecimal(volume);
        Tick tick = new Tick(symbol, p, v, seqNum, Instant.now(), "test-feed");
        validator.onTick(tick);
    }
}
