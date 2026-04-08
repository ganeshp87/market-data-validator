package com.marketdata.validator.controller;

import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.validator.ValidatorEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * REST controller exposing MiFID-II / ESMA-compliant compliance metrics.
 *
 * Blueprint Section 4 — ComplianceController:
 *   GET /api/compliance — returns 6 MiFID-II health fields derived from validator results.
 *
 * Field mapping:
 *   art27TimestampAccuracy    ← LATENCY validator  (p95 < 100ms → PASS)
 *   art64PreTradeTransparency ← ACCURACY validator  (PASS → PASS)
 *   art65PostTradeReporting   ← COMPLETENESS validator (gapEvents = 0 → PASS)
 *   rts22TradeIdUniqueness    ← ORDERING validator  (duplicates = 0 → PASS)
 *   auditTrailCompleteness    ← COMPLETENESS validator (completenessRate ≥ 99.99% → PASS)
 *   ohlcReconciliation        ← STATEFUL validator  (PASS → PASS)
 */
@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    private static final long ART27_LATENCY_THRESHOLD_MS = 100;

    private final ValidatorEngine validatorEngine;

    public ComplianceController(ValidatorEngine validatorEngine) {
        this.validatorEngine = validatorEngine;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getCompliance() {
        Map<String, ValidationResult> byArea = validatorEngine.getResultsByArea();

        ValidationResult latency = byArea.get("LATENCY");
        ValidationResult accuracy = byArea.get("ACCURACY");
        ValidationResult completeness = byArea.get("COMPLETENESS");
        ValidationResult ordering = byArea.get("ORDERING");
        ValidationResult stateful = byArea.get("STATEFUL");

        String art27 = deriveArt27(latency);
        String art64 = deriveStatus(accuracy);
        String art65 = deriveArt65(completeness);
        String rts22 = deriveRts22(ordering);
        String auditTrail = deriveAuditTrail(completeness);
        String ohlc = deriveStatus(stateful);

        Map<String, Object> report = Map.of(
                "timestamp", Instant.now(),
                "art27TimestampAccuracy", art27,
                "art64PreTradeTransparency", art64,
                "art65PostTradeReporting", art65,
                "rts22TradeIdUniqueness", rts22,
                "auditTrailCompleteness", auditTrail,
                "ohlcReconciliation", ohlc
        );

        return ResponseEntity.ok(report);
    }

    // --- Derivation helpers ---

    /** ART 27: timestamp accuracy PASS when p95 latency < 100ms */
    private String deriveArt27(ValidationResult latency) {
        if (latency == null) return "UNKNOWN";
        Object p95raw = latency.getDetails() != null ? latency.getDetails().get("p95") : null;
        if (p95raw instanceof Number p95) {
            return p95.longValue() < ART27_LATENCY_THRESHOLD_MS ? "PASS" : "FAIL";
        }
        return statusName(latency.getStatus());
    }

    /** ART 65: post-trade reporting PASS when there are 0 gap events */
    private String deriveArt65(ValidationResult completeness) {
        if (completeness == null) return "UNKNOWN";
        Object gaps = completeness.getDetails() != null ? completeness.getDetails().get("gapEventCount") : null;
        if (gaps instanceof Number gapNum) {
            return gapNum.longValue() == 0 ? "PASS" : "FAIL";
        }
        return statusName(completeness.getStatus());
    }

    /** RTS 22: trade ID uniqueness PASS when no duplicate ordering violations */
    private String deriveRts22(ValidationResult ordering) {
        if (ordering == null) return "UNKNOWN";
        Object dups = ordering.getDetails() != null ? ordering.getDetails().get("duplicateCount") : null;
        if (dups instanceof Number dupNum) {
            return dupNum.longValue() == 0 ? "PASS" : "FAIL";
        }
        return statusName(ordering.getStatus());
    }

    /** Audit Trail: PASS when completeness rate ≥ 99.99% */
    private String deriveAuditTrail(ValidationResult completeness) {
        if (completeness == null) return "UNKNOWN";
        Object rateRaw = completeness.getDetails() != null ? completeness.getDetails().get("completenessRate") : null;
        if (rateRaw instanceof Number rate) {
            double r = rate.doubleValue();
            if (r >= 99.99) return "PASS";
            if (r >= 99.0) return "WARN";
            return "FAIL";
        }
        return statusName(completeness.getStatus());
    }

    private String deriveStatus(ValidationResult result) {
        if (result == null) return "UNKNOWN";
        return statusName(result.getStatus());
    }

    private String statusName(ValidationResult.Status status) {
        return status == null ? "UNKNOWN" : status.name();
    }
}
