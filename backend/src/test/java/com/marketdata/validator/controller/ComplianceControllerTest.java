package com.marketdata.validator.controller;

import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.model.ValidationResult.Area;
import com.marketdata.validator.model.ValidationResult.Status;
import com.marketdata.validator.validator.ValidatorEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ComplianceControllerTest {

    private ComplianceController controller;
    private ValidatorEngine engine;

    @BeforeEach
    void setUp() {
        engine = mock(ValidatorEngine.class);
        controller = new ComplianceController(engine);
    }

    @Test
    void returnsAllSixMiFIDFields() {
        when(engine.getResultsByArea()).thenReturn(Map.of());
        ResponseEntity<Map<String, Object>> response = controller.getCompliance();
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = response.getBody();
        assertThat(body).containsKeys(
                "timestamp",
                "art27TimestampAccuracy",
                "art64PreTradeTransparency",
                "art65PostTradeReporting",
                "rts22TradeIdUniqueness",
                "auditTrailCompleteness",
                "ohlcReconciliation"
        );
    }

    @Test
    void art27PassWhenP95Below100ms() {
        ValidationResult latency = ValidationResult.pass(Area.LATENCY, "ok", 0.0, 0.0);
        latency.getDetails().put("p95", 50L);
        when(engine.getResultsByArea()).thenReturn(Map.of("LATENCY", latency));

        ResponseEntity<Map<String, Object>> response = controller.getCompliance();
        assertThat(response.getBody().get("art27TimestampAccuracy")).isEqualTo("PASS");
    }

    @Test
    void art27FailWhenP95Above100ms() {
        ValidationResult latency = ValidationResult.pass(Area.LATENCY, "ok", 0.0, 0.0);
        latency.getDetails().put("p95", 250L);
        when(engine.getResultsByArea()).thenReturn(Map.of("LATENCY", latency));

        ResponseEntity<Map<String, Object>> response = controller.getCompliance();
        assertThat(response.getBody().get("art27TimestampAccuracy")).isEqualTo("FAIL");
    }

    @Test
    void art65PassWhenZeroGapEvents() {
        ValidationResult completeness = ValidationResult.pass(Area.COMPLETENESS, "ok", 0.0, 0.0);
        completeness.getDetails().put("gapEventCount", 0L);
        when(engine.getResultsByArea()).thenReturn(Map.of("COMPLETENESS", completeness));

        ResponseEntity<Map<String, Object>> response = controller.getCompliance();
        assertThat(response.getBody().get("art65PostTradeReporting")).isEqualTo("PASS");
    }

    @Test
    void art65FailWhenGapEventsExist() {
        ValidationResult completeness = ValidationResult.fail(Area.COMPLETENESS, "gaps found", 95.0, 99.0);
        completeness.getDetails().put("gapEventCount", 3L);
        when(engine.getResultsByArea()).thenReturn(Map.of("COMPLETENESS", completeness));

        ResponseEntity<Map<String, Object>> response = controller.getCompliance();
        assertThat(response.getBody().get("art65PostTradeReporting")).isEqualTo("FAIL");
    }

    @Test
    void unknownWhenValidatorResultsMissing() {
        when(engine.getResultsByArea()).thenReturn(Map.of());

        ResponseEntity<Map<String, Object>> response = controller.getCompliance();
        Map<String, Object> body = response.getBody();
        assertThat(body.get("art27TimestampAccuracy")).isEqualTo("UNKNOWN");
        assertThat(body.get("art64PreTradeTransparency")).isEqualTo("UNKNOWN");
    }

    @Test
    void auditTrailWarnForPartialCompleteness() {
        ValidationResult completeness = new ValidationResult(
                Area.COMPLETENESS, Status.WARN, "partial", 99.5, 99.99);
        completeness.getDetails().put("completenessRate", 99.5);
        when(engine.getResultsByArea()).thenReturn(Map.of("COMPLETENESS", completeness));

        ResponseEntity<Map<String, Object>> response = controller.getCompliance();
        assertThat(response.getBody().get("auditTrailCompleteness")).isEqualTo("WARN");
    }
}
