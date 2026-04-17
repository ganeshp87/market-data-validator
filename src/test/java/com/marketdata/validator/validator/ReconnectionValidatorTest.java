package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ReconnectionValidatorTest {

    private ReconnectionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ReconnectionValidator();
    }

    // --- Area ---

    @Test
    void areaIsReconnection() {
        assertThat(validator.getArea()).isEqualTo("RECONNECTION");
    }

    // --- No events → PASS ---

    @Test
    void noEventsProducesPass() {
        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
        assertThat(result.getMessage()).contains("No connection events");
    }

    // --- onTick is a no-op ---

    @Test
    void onTickIsNoOp() {
        Tick tick = new Tick("BTCUSDT", new BigDecimal("45000"), new BigDecimal("1"),
                1, Instant.now(), "test-feed");
        validator.onTick(tick);

        // No state changed — still "no events"
        assertThat(validator.getDisconnectCount()).isEqualTo(0);
        assertThat(validator.getReconnectCount()).isEqualTo(0);
    }

    // --- Disconnect tracking ---

    @Test
    void disconnectRecorded() {
        validator.onDisconnect("conn-1");

        assertThat(validator.getDisconnectCount()).isEqualTo(1);
    }

    @Test
    void multipleDisconnectsCounted() {
        validator.onDisconnect("conn-1");
        validator.onDisconnect("conn-1");
        validator.onDisconnect("conn-2");

        assertThat(validator.getDisconnectCount()).isEqualTo(3);
    }

    // --- Reconnect tracking ---

    @Test
    void reconnectRecorded() {
        validator.onDisconnect("conn-1");
        validator.onReconnect("conn-1", Duration.ofSeconds(2));

        assertThat(validator.getReconnectCount()).isEqualTo(1);
        assertThat(validator.getAvgReconnectTimeMs()).isCloseTo(2000.0, within(1.0));
    }

    // --- PASS: all disconnects reconnected within threshold ---

    @Test
    void allReconnectedWithinThresholdProducesPass() {
        validator.onDisconnect("conn-1");
        validator.onReconnect("conn-1", Duration.ofSeconds(2)); // 2s < 5s threshold

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    // --- WARN: reconnects < disconnects ---

    @Test
    void unresolvedDisconnectProducesWarn() {
        validator.onDisconnect("conn-1");
        // No reconnect — pending

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.WARN);
    }

    // --- WARN: avg reconnect time > threshold ---

    @Test
    void slowReconnectProducesWarn() {
        validator.onDisconnect("conn-1");
        validator.onReconnect("conn-1", Duration.ofSeconds(8)); // 8s > 5s threshold

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.WARN);
    }

    // --- FAIL: failed reconnects ---

    @Test
    void failedReconnectProducesFail() {
        validator.onDisconnect("conn-1");
        validator.onReconnectFailed("conn-1");

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.FAIL);
    }

    @Test
    void failedReconnectTrumpsEverything() {
        // Even with successful reconnects, one failure → FAIL
        validator.onDisconnect("conn-1");
        validator.onReconnect("conn-1", Duration.ofSeconds(1));
        validator.onDisconnect("conn-2");
        validator.onReconnectFailed("conn-2");

        assertThat(validator.getResult().getStatus()).isEqualTo(ValidationResult.Status.FAIL);
    }

    // --- Subscription restoration ---

    @Test
    void subscriptionRestorationTracked() {
        validator.onDisconnect("conn-1");
        validator.onReconnect("conn-1", Duration.ofSeconds(2));
        validator.onSubscriptionRestored("conn-1");

        assertThat(validator.getSubscriptionRestorations()).isEqualTo(1);
    }

    // --- Average reconnect time calculation ---

    @Test
    void avgReconnectTimeAcrossMultipleEvents() {
        validator.onDisconnect("conn-1");
        validator.onReconnect("conn-1", Duration.ofSeconds(2));
        validator.onDisconnect("conn-1");
        validator.onReconnect("conn-1", Duration.ofSeconds(4));

        // avg = (2000 + 4000) / 2 = 3000ms
        assertThat(validator.getAvgReconnectTimeMs()).isCloseTo(3000.0, within(1.0));
    }

    @Test
    void avgReconnectTimeZeroWithNoReconnects() {
        assertThat(validator.getAvgReconnectTimeMs()).isEqualTo(0.0);
    }

    // --- Metric ---

    @Test
    void metricIsReconnectRatePercent() {
        validator.onDisconnect("conn-1");
        validator.onDisconnect("conn-2");
        validator.onReconnect("conn-1", Duration.ofSeconds(1));

        // 1 reconnect out of 2 disconnects = 50%
        ValidationResult result = validator.getResult();
        assertThat(result.getMetric()).isCloseTo(50.0, within(0.1));
    }

    // --- Reset ---

    @Test
    void resetClearsAllState() {
        validator.onDisconnect("conn-1");
        validator.onReconnect("conn-1", Duration.ofSeconds(3));
        validator.onReconnectFailed("conn-2");
        validator.onSubscriptionRestored("conn-1");

        validator.reset();

        assertThat(validator.getDisconnectCount()).isEqualTo(0);
        assertThat(validator.getReconnectCount()).isEqualTo(0);
        assertThat(validator.getFailedReconnects()).isEqualTo(0);
        assertThat(validator.getSubscriptionRestorations()).isEqualTo(0);
        assertThat(validator.getAvgReconnectTimeMs()).isEqualTo(0.0);
        assertThat(validator.getResult().getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    // --- Configure ---

    @Test
    void configureChangesReconnectThreshold() {
        validator.configure(Map.of("reconnectThresholdMs", 2000L)); // 2s threshold

        validator.onDisconnect("conn-1");
        validator.onReconnect("conn-1", Duration.ofSeconds(3)); // 3s > 2s → WARN

        assertThat(validator.getResult().getStatus()).isEqualTo(ValidationResult.Status.WARN);
    }

    // --- Result details ---

    @Test
    void resultContainsDetailedMetrics() {
        validator.onDisconnect("conn-1");
        validator.onReconnect("conn-1", Duration.ofSeconds(2));

        ValidationResult result = validator.getResult();
        assertThat(result.getDetails()).containsKey("disconnectCount");
        assertThat(result.getDetails()).containsKey("reconnectCount");
        assertThat(result.getDetails()).containsKey("failedReconnects");
        assertThat(result.getDetails()).containsKey("avgReconnectTimeMs");
        assertThat(result.getDetails()).containsKey("subscriptionRestorations");
    }
}
