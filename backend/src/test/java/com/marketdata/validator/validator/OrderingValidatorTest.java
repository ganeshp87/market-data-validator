package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrderingValidatorTest {

    private OrderingValidator validator;

    @BeforeEach
    void setUp() {
        validator = new OrderingValidator();
    }

    // --- Area ---

    @Test
    void areaIsOrdering() {
        assertThat(validator.getArea()).isEqualTo("ORDERING");
    }

    // --- In-order ticks → PASS ---

    @Test
    void inOrderTicksProducesPass() {
        Instant base = Instant.parse("2026-03-23T10:00:00Z");

        feedTick("BTCUSDT", base, 1);
        feedTick("BTCUSDT", base.plusMillis(100), 2);
        feedTick("BTCUSDT", base.plusMillis(200), 3);

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
        assertThat(result.getMetric()).isEqualTo(100.0);
    }

    @Test
    void noTicksProducesPassWithDefaultMessage() {
        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
        assertThat(result.getMessage()).contains("No ticks processed");
    }

    // --- Out-of-order ticks ---

    @Test
    void outOfOrderTickDetected() {
        Instant base = Instant.parse("2026-03-23T10:00:00Z");

        feedTick("BTCUSDT", base.plusMillis(200), 1);
        feedTick("BTCUSDT", base.plusMillis(100), 2); // OUT OF ORDER
        feedTick("BTCUSDT", base.plusMillis(300), 3);

        assertThat(validator.getOutOfOrderCount()).isEqualTo(1);
        assertThat(validator.getTotalTicks()).isEqualTo(3);
    }

    @Test
    void multipleOutOfOrderTicksCountedCorrectly() {
        Instant base = Instant.parse("2026-03-23T10:00:00Z");

        feedTick("BTCUSDT", base.plusMillis(300), 1); // lastTs=300
        feedTick("BTCUSDT", base.plusMillis(100), 2); // 100 < 300 → out of order, lastTs=100
        feedTick("BTCUSDT", base.plusMillis(50),  3); // 50 < 100  → out of order, lastTs=50
        feedTick("BTCUSDT", base.plusMillis(400), 4); // 400 > 50  → in order

        assertThat(validator.getOutOfOrderCount()).isEqualTo(2);
    }

    // --- Per-symbol tracking ---

    @Test
    void orderingTrackedPerSymbol() {
        Instant base = Instant.parse("2026-03-23T10:00:00Z");

        // BTC goes forward, ETH goes forward — even though ETH timestamps < BTC
        feedTick("BTCUSDT", base.plusMillis(200), 1);
        feedTick("ETHUSDT", base.plusMillis(100), 2);  // Different symbol — not out of order
        feedTick("BTCUSDT", base.plusMillis(300), 3);
        feedTick("ETHUSDT", base.plusMillis(150), 4);

        assertThat(validator.getOutOfOrderCount()).isEqualTo(0);
    }

    // --- Status thresholds ---

    @Test
    void orderingRateBelowWarnThresholdProducesFail() {
        Instant base = Instant.parse("2026-03-23T10:00:00Z");

        // 5 out of 10 ticks out of order → 50% ordering rate → FAIL (< 99.0%)
        for (int i = 0; i < 10; i++) {
            long ms = (i % 2 == 0) ? i * 100 : (10 - i) * 100;
            feedTick("BTCUSDT", base.plusMillis(ms), i + 1);
        }

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.FAIL);
    }

    // --- Idempotent processing ---

    @Test
    void duplicateSequenceNumbersSkipped() {
        Instant base = Instant.parse("2026-03-23T10:00:00Z");

        feedTick("BTCUSDT", base, 1);
        feedTick("BTCUSDT", base.plusMillis(100), 2);
        feedTick("BTCUSDT", base.plusMillis(50), 2);  // Duplicate seq 2 — should be skipped
        feedTick("BTCUSDT", base.plusMillis(200), 3);

        assertThat(validator.getTotalTicks()).isEqualTo(3); // Not 4
        assertThat(validator.getOutOfOrderCount()).isEqualTo(0);
    }

    @Test
    void olderSequenceNumbersSkipped() {
        Instant base = Instant.parse("2026-03-23T10:00:00Z");

        feedTick("BTCUSDT", base, 5);
        feedTick("BTCUSDT", base.plusMillis(100), 3);  // seq 3 < 5 — skipped
        feedTick("BTCUSDT", base.plusMillis(200), 6);

        assertThat(validator.getTotalTicks()).isEqualTo(2); // Skipped seq 3
    }

    // --- Business rules: bid <= ask ---

    @Test
    void bidGreaterThanAskCounted() {
        Tick tick = createTick("BTCUSDT", Instant.now(), 1);
        tick.setBid(new BigDecimal("100.50"));
        tick.setAsk(new BigDecimal("100.00"));  // bid > ask → violation
        validator.onTick(tick);

        assertThat(validator.getBidAskViolations()).isEqualTo(1);
    }

    @Test
    void bidLessOrEqualAskNoViolation() {
        Tick tick = createTick("BTCUSDT", Instant.now(), 1);
        tick.setBid(new BigDecimal("100.00"));
        tick.setAsk(new BigDecimal("100.50"));
        validator.onTick(tick);

        assertThat(validator.getBidAskViolations()).isEqualTo(0);
    }

    @Test
    void bidAskBothNullNoViolation() {
        feedTick("BTCUSDT", Instant.now(), 1);
        assertThat(validator.getBidAskViolations()).isEqualTo(0);
    }

    // --- Business rules: volume >= 0 ---

    @Test
    void negativeVolumeCounted() {
        Tick tick = createTick("BTCUSDT", Instant.now(), 1);
        tick.setVolume(new BigDecimal("-1"));
        validator.onTick(tick);

        assertThat(validator.getVolumeViolations()).isEqualTo(1);
    }

    @Test
    void zeroVolumeNoViolation() {
        Tick tick = createTick("BTCUSDT", Instant.now(), 1);
        tick.setVolume(BigDecimal.ZERO);
        validator.onTick(tick);

        assertThat(validator.getVolumeViolations()).isEqualTo(0);
    }

    // --- Reset ---

    @Test
    void resetClearsAllState() {
        feedTick("BTCUSDT", Instant.now(), 1);
        feedTick("BTCUSDT", Instant.now().minusSeconds(1), 2); // out of order

        assertThat(validator.getTotalTicks()).isEqualTo(2);
        assertThat(validator.getOutOfOrderCount()).isEqualTo(1);

        validator.reset();

        assertThat(validator.getTotalTicks()).isEqualTo(0);
        assertThat(validator.getOutOfOrderCount()).isEqualTo(0);
        assertThat(validator.getResult().getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    // --- Configure ---

    @Test
    void configureChangesThresholds() {
        validator.configure(Map.of("passThreshold", 95.0, "warnThreshold", 90.0));

        Instant base = Instant.parse("2026-03-23T10:00:00Z");

        // 1 out of 20 ticks out of order → 95% → PASS with new threshold
        feedTick("BTCUSDT", base.plusMillis(100), 1);
        feedTick("BTCUSDT", base, 2); // out of order
        for (int i = 3; i <= 20; i++) {
            feedTick("BTCUSDT", base.plusMillis(100 + i * 10), i);
        }

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    // --- Result details ---

    @Test
    void resultContainsDetailedMetrics() {
        feedTick("BTCUSDT", Instant.now(), 1);

        ValidationResult result = validator.getResult();
        assertThat(result.getDetails()).containsKey("totalTicks");
        assertThat(result.getDetails()).containsKey("outOfOrderCount");
        assertThat(result.getDetails()).containsKey("bidAskViolations");
        assertThat(result.getDetails()).containsKey("volumeViolations");
        assertThat(result.getDetails()).containsKey("orderingRate");
    }

    // --- Helpers ---

    private void feedTick(String symbol, Instant exchangeTs, long seqNum) {
        validator.onTick(createTick(symbol, exchangeTs, seqNum));
    }

    private Tick createTick(String symbol, Instant exchangeTs, long seqNum) {
        Tick tick = new Tick(symbol, new BigDecimal("45000"), new BigDecimal("1"),
                seqNum, exchangeTs, "test-feed");
        return tick;
    }
}
