package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AccuracyValidatorTest {

    private AccuracyValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AccuracyValidator();
    }

    // --- Area ---

    @Test
    void areaIsAccuracy() {
        assertThat(validator.getArea()).isEqualTo("ACCURACY");
    }

    // --- Valid ticks → PASS ---

    @Test
    void validTicksProducesPass() {
        feedTick("BTCUSDT", "45000.00", 1);
        feedTick("BTCUSDT", "45100.00", 2); // < 10% move
        feedTick("BTCUSDT", "45200.00", 3);

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
        assertThat(result.getMetric()).isEqualTo(100.0);
    }

    @Test
    void noTicksProducesPass() {
        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
        assertThat(result.getMessage()).contains("No ticks processed");
    }

    // --- Rule 1: Price must be positive ---

    @Test
    void zeroPriceIsInvalid() {
        feedTick("BTCUSDT", "0", 1);

        assertThat(validator.getInvalidPriceCount()).isEqualTo(1);
        assertThat(validator.getValidTicks()).isEqualTo(0);
    }

    @Test
    void negativePriceIsInvalid() {
        feedTick("BTCUSDT", "-100.50", 1);

        assertThat(validator.getInvalidPriceCount()).isEqualTo(1);
        assertThat(validator.getValidTicks()).isEqualTo(0);
    }

    @Test
    void nullPriceIsInvalid() {
        Tick tick = new Tick("BTCUSDT", null, new BigDecimal("1"), 1, Instant.now(), "test-feed");
        validator.onTick(tick);

        assertThat(validator.getInvalidPriceCount()).isEqualTo(1);
    }

    // --- Rule 2: Bid <= Ask ---

    @Test
    void bidGreaterThanAskIsViolation() {
        Tick tick = createTick("BTCUSDT", "45000.00", 1);
        tick.setBid(new BigDecimal("45100.00"));
        tick.setAsk(new BigDecimal("45000.00")); // bid > ask
        validator.onTick(tick);

        assertThat(validator.getBidAskViolations()).isEqualTo(1);
        assertThat(validator.getValidTicks()).isEqualTo(0);
    }

    @Test
    void bidLessOrEqualAskIsValid() {
        Tick tick = createTick("BTCUSDT", "45000.00", 1);
        tick.setBid(new BigDecimal("44900.00"));
        tick.setAsk(new BigDecimal("45100.00"));
        validator.onTick(tick);

        assertThat(validator.getBidAskViolations()).isEqualTo(0);
        assertThat(validator.getValidTicks()).isEqualTo(1);
    }

    @Test
    void nullBidAskNoViolation() {
        feedTick("BTCUSDT", "45000.00", 1);
        assertThat(validator.getBidAskViolations()).isEqualTo(0);
    }

    // --- Rule 3: Large move detection (> 10%) ---

    @Test
    void largeMoveDetected() {
        feedTick("BTCUSDT", "100.00", 1);
        feedTick("BTCUSDT", "115.00", 2); // 15% increase > 10% threshold

        assertThat(validator.getLargeMoveCount()).isEqualTo(1);
    }

    @Test
    void moveWithin10PercentIsValid() {
        feedTick("BTCUSDT", "100.00", 1);
        feedTick("BTCUSDT", "109.00", 2); // 9% increase < 10% threshold

        assertThat(validator.getLargeMoveCount()).isEqualTo(0);
        assertThat(validator.getValidTicks()).isEqualTo(2);
    }

    @Test
    void exactlyTenPercentMoveIsNotLarge() {
        feedTick("BTCUSDT", "100.00", 1);
        feedTick("BTCUSDT", "110.00", 2); // exactly 10% = not > 10%

        assertThat(validator.getLargeMoveCount()).isEqualTo(0);
    }

    @Test
    void firstTickForSymbolNeverLargeMove() {
        feedTick("BTCUSDT", "45000.00", 1);
        assertThat(validator.getLargeMoveCount()).isEqualTo(0);
        assertThat(validator.getValidTicks()).isEqualTo(1);
    }

    @Test
    void largeMoveTrackedPerSymbol() {
        feedTick("BTCUSDT", "100.00", 1);
        feedTick("ETHUSDT", "200.00", 2);       // First tick for ETH — no large move
        feedTick("BTCUSDT", "105.00", 3);        // 5% move for BTC — valid
        feedTick("ETHUSDT", "210.00", 4);         // 5% move for ETH — valid

        assertThat(validator.getLargeMoveCount()).isEqualTo(0);
        assertThat(validator.getValidTicks()).isEqualTo(4);
    }

    // --- Idempotent processing ---

    @Test
    void duplicateSequenceNumberSkipped() {
        feedTick("BTCUSDT", "45000.00", 1);
        feedTick("BTCUSDT", "45000.00", 1); // duplicate seq — skipped

        assertThat(validator.getTotalTicks()).isEqualTo(1);
    }

    @Test
    void olderSequenceNumberSkipped() {
        feedTick("BTCUSDT", "45000.00", 5);
        feedTick("BTCUSDT", "45000.00", 3); // older seq — skipped

        assertThat(validator.getTotalTicks()).isEqualTo(1);
    }

    // --- Combined accuracy rate ---

    @Test
    void accuracyRateCalculatedCorrectly() {
        feedTick("BTCUSDT", "100.00", 1);  // valid
        feedTick("BTCUSDT", "105.00", 2);  // valid (5%)
        feedTick("BTCUSDT", "0", 3);       // invalid (zero price)

        // 2 valid out of 3 total = 66.67%
        ValidationResult result = validator.getResult();
        assertThat(result.getMetric()).isCloseTo(66.67, org.assertj.core.api.Assertions.within(0.1));
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.FAIL);
    }

    @Test
    void warnStatusWithinRange() {
        // Create 100 ticks: 99 valid, 1 invalid → 99.0% → WARN (>= 99.0 but < 99.9)
        for (int i = 1; i <= 99; i++) {
            BigDecimal price = new BigDecimal("100.00").add(new BigDecimal("0.01").multiply(new BigDecimal(i)));
            feedTick("BTCUSDT", price.toPlainString(), i);
        }
        feedTick("BTCUSDT", "0", 100); // invalid

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.WARN);
    }

    // --- Reset ---

    @Test
    void resetClearsAllState() {
        feedTick("BTCUSDT", "45000.00", 1);
        feedTick("BTCUSDT", "0", 2);

        assertThat(validator.getTotalTicks()).isEqualTo(2);

        validator.reset();

        assertThat(validator.getTotalTicks()).isEqualTo(0);
        assertThat(validator.getValidTicks()).isEqualTo(0);
        assertThat(validator.getInvalidPriceCount()).isEqualTo(0);
        assertThat(validator.getBidAskViolations()).isEqualTo(0);
        assertThat(validator.getLargeMoveCount()).isEqualTo(0);
        assertThat(validator.getResult().getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    // --- Configure ---

    @Test
    void configureChangesLargeMoveThreshold() {
        validator.configure(Map.of("largeMovePercent", "0.05")); // 5% threshold

        feedTick("BTCUSDT", "100.00", 1);
        feedTick("BTCUSDT", "107.00", 2); // 7% > 5% new threshold

        assertThat(validator.getLargeMoveCount()).isEqualTo(1);
    }

    @Test
    void configureChangesPassWarnThresholds() {
        validator.configure(Map.of("passThreshold", 95.0, "warnThreshold", 90.0));

        // 1 invalid out of 20 = 95% → PASS with relaxed threshold
        for (int i = 1; i <= 19; i++) {
            feedTick("BTCUSDT", "100.00", i);
        }
        feedTick("BTCUSDT", "0", 20);

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    // --- Result details ---

    @Test
    void resultContainsDetailedMetrics() {
        feedTick("BTCUSDT", "45000.00", 1);

        ValidationResult result = validator.getResult();
        assertThat(result.getDetails()).containsKey("totalTicks");
        assertThat(result.getDetails()).containsKey("validTicks");
        assertThat(result.getDetails()).containsKey("invalidPriceCount");
        assertThat(result.getDetails()).containsKey("bidAskViolations");
        assertThat(result.getDetails()).containsKey("largeMoveCount");
        assertThat(result.getDetails()).containsKey("accuracyRate");
    }

    // --- Helpers ---

    private void feedTick(String symbol, String price, long seqNum) {
        validator.onTick(createTick(symbol, price, seqNum));
    }

    private Tick createTick(String symbol, String price, long seqNum) {
        BigDecimal p = (price == null) ? null : new BigDecimal(price);
        return new Tick(symbol, p, new BigDecimal("1"), seqNum, Instant.now(), "test-feed");
    }
}
