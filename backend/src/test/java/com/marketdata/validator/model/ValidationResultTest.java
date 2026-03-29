package com.marketdata.validator.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationResultTest {

    @Test
    void areaEnumHasAll8Values() {
        // Blueprint specifies exactly 8 validation areas
        assertThat(ValidationResult.Area.values()).hasSize(8);
        assertThat(ValidationResult.Area.values()).containsExactly(
                ValidationResult.Area.ACCURACY,
                ValidationResult.Area.LATENCY,
                ValidationResult.Area.COMPLETENESS,
                ValidationResult.Area.RECONNECTION,
                ValidationResult.Area.THROUGHPUT,
                ValidationResult.Area.ORDERING,
                ValidationResult.Area.SUBSCRIPTION,
                ValidationResult.Area.STATEFUL
        );
    }

    @Test
    void statusEnumHasThreeValues() {
        assertThat(ValidationResult.Status.values()).containsExactly(
                ValidationResult.Status.PASS,
                ValidationResult.Status.WARN,
                ValidationResult.Status.FAIL
        );
    }

    @Test
    void constructorSetsTimestampAutomatically() {
        Instant before = Instant.now();
        ValidationResult result = new ValidationResult(
                ValidationResult.Area.ACCURACY,
                ValidationResult.Status.PASS,
                "99.98% accuracy", 99.98, 99.9);
        Instant after = Instant.now();

        assertThat(result.getTimestamp()).isBetween(before, after);
    }

    @Test
    void constructorSetsAllFields() {
        ValidationResult result = new ValidationResult(
                ValidationResult.Area.LATENCY,
                ValidationResult.Status.WARN,
                "p95 latency 42ms (threshold: 500ms)",
                42.0, 500.0);

        assertThat(result.getArea()).isEqualTo(ValidationResult.Area.LATENCY);
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.WARN);
        assertThat(result.getMessage()).isEqualTo("p95 latency 42ms (threshold: 500ms)");
        assertThat(result.getMetric()).isEqualTo(42.0);
        assertThat(result.getThreshold()).isEqualTo(500.0);
    }

    @Test
    void defaultConstructorInitializesDetailsMap() {
        ValidationResult result = new ValidationResult();
        assertThat(result.getDetails()).isNotNull();
        assertThat(result.getDetails()).isEmpty();
    }

    @Test
    void detailsMapAcceptsArbitraryData() {
        ValidationResult result = new ValidationResult();
        result.getDetails().put("gapCount", 3);
        result.getDetails().put("staleFeed", "BTCUSDT");
        result.getDetails().put("violations", java.util.List.of("bid > ask at seq 1001"));

        assertThat(result.getDetails()).hasSize(3);
        assertThat(result.getDetails().get("gapCount")).isEqualTo(3);
    }

    @Test
    void passFactoryMethod() {
        ValidationResult result = ValidationResult.pass(
                ValidationResult.Area.ORDERING,
                "100% in order", 100.0, 99.99);

        assertThat(result.getArea()).isEqualTo(ValidationResult.Area.ORDERING);
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
        assertThat(result.getMessage()).isEqualTo("100% in order");
        assertThat(result.getMetric()).isEqualTo(100.0);
        assertThat(result.getThreshold()).isEqualTo(99.99);
        assertThat(result.getTimestamp()).isNotNull();
    }

    @Test
    void warnFactoryMethod() {
        ValidationResult result = ValidationResult.warn(
                ValidationResult.Area.COMPLETENESS,
                "2 gaps detected", 2.0, 0.0);

        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.WARN);
        assertThat(result.getArea()).isEqualTo(ValidationResult.Area.COMPLETENESS);
    }

    @Test
    void failFactoryMethod() {
        ValidationResult result = ValidationResult.fail(
                ValidationResult.Area.RECONNECTION,
                "Failed reconnect", 0.0, 1.0);

        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.FAIL);
        assertThat(result.getArea()).isEqualTo(ValidationResult.Area.RECONNECTION);
    }

    @Test
    void settersAndGettersWorkForAllFields() {
        ValidationResult result = new ValidationResult();
        Instant now = Instant.now();

        result.setArea(ValidationResult.Area.STATEFUL);
        result.setStatus(ValidationResult.Status.FAIL);
        result.setMessage("VWAP diverged");
        result.setMetric(95.5);
        result.setThreshold(99.9);
        result.setTimestamp(now);
        result.setDetails(Map.of("symbol", "BTCUSDT"));

        assertThat(result.getArea()).isEqualTo(ValidationResult.Area.STATEFUL);
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.FAIL);
        assertThat(result.getMessage()).isEqualTo("VWAP diverged");
        assertThat(result.getMetric()).isEqualTo(95.5);
        assertThat(result.getThreshold()).isEqualTo(99.9);
        assertThat(result.getTimestamp()).isEqualTo(now);
        assertThat(result.getDetails()).containsEntry("symbol", "BTCUSDT");
    }

    @Test
    void statefulAreaExistsForFinTechValidator() {
        // Critical: STATEFUL is the 8th validator — validates reconstructed state
        ValidationResult result = ValidationResult.pass(
                ValidationResult.Area.STATEFUL,
                "VWAP, OHLC, cumulative volume all consistent",
                99.99, 99.99);

        assertThat(result.getArea().name()).isEqualTo("STATEFUL");
    }
}
