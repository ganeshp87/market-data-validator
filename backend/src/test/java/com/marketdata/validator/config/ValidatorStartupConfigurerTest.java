package com.marketdata.validator.config;

import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.validator.ValidatorEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms that validator thresholds configured in application.properties
 * are applied at startup without requiring a PUT /api/validation/config call.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // Override latency threshold to 200ms to verify startup config is applied
        "validator.latency.warn-threshold-ms=200"
})
class ValidatorStartupConfigurerTest {

    @Autowired
    private ValidatorEngine engine;

    @Test
    void latencyThresholdAppliedFromPropertiesAtStartup() {
        // The custom threshold (200ms) must be active without any API call.
        // Feed ticks at 250ms latency — should trigger WARN since 250 >= 200ms threshold.
        engine.reset();
        for (int i = 1; i <= 100; i++) {
            Instant exchangeTs = Instant.parse("2026-01-01T00:00:00Z");
            Instant receivedTs = exchangeTs.plusMillis(250); // 250ms > 200ms threshold

            com.marketdata.validator.model.Tick tick =
                    new com.marketdata.validator.model.Tick("BTCUSDT",
                            new BigDecimal("45000"), new BigDecimal("1"),
                            i, exchangeTs, "test-feed");
            tick.setReceivedTimestamp(receivedTs);
            engine.onTick(tick);
        }

        ValidationResult latencyResult = engine.getResultsByArea().get("LATENCY");
        assertThat(latencyResult).isNotNull();
        // 250ms >= 200ms threshold → at minimum WARN; default 500ms threshold would give PASS
        assertThat(latencyResult.getStatus())
                .as("250ms latency must be WARN when threshold is 200ms (set via application.properties)")
                .isNotEqualTo(ValidationResult.Status.PASS);
    }
}
