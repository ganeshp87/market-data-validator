package com.marketdata.validator.simulator;

import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.model.ValidationResult.Status;
import com.marketdata.validator.validator.AccuracyValidator;
import com.marketdata.validator.validator.CompletenessValidator;
import com.marketdata.validator.validator.LatencyValidator;
import com.marketdata.validator.validator.ValidatorEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests that wire LVWRChaosSimulator → ValidatorEngine → assert validator state.
 *
 * Blueprint QE Section 2 — Failure Type → Validator → UI Mapping (verified end-to-end).
 *
 * Each test proves that a specific SCENARIO-mode failure type changes the corresponding
 * validator's status from PASS to WARN/FAIL. This is the gap the unit tests alone cannot fill:
 * they verify tick properties in isolation; these tests verify the full pipeline reaction.
 */
class SimulatorValidatorIntegrationTest {

    /** Build a SCENARIO config with failureRate=1.0 so every tick injects the target failure. */
    private static ScenarioConfig scenarioConfig(FailureType target, int numTrades) {
        ScenarioConfig cfg = new ScenarioConfig();
        cfg.setMode(SimulatorMode.SCENARIO);
        cfg.setTargetScenario(target);
        cfg.setFailureRate(1.0);
        cfg.setNumTrades(numTrades);
        cfg.setTicksPerSecond(1000); // fast — no waiting
        cfg.setIncludeHeartbeats(false);
        return cfg;
    }

    /**
     * SEQUENCE_GAP → CompletenessValidator.
     *
     * With 25 trades the instrument round-robin (17 instruments) produces 8 second-appearances.
     * Each SEQUENCE_GAP tick skips 5 per-symbol seqNums (advanceSeqNum(5) + nextSeqNum()),
     * so the second appearance of any symbol has seqNum = firstSeqNum + 6, reporting 5 missing
     * seqNums per gap event.  completenessRate drops below 99.99 % → status != PASS.
     */
    @Test
    void seqGapScenarioCausesCompletenessValidatorToDetectGaps() throws InterruptedException {
        CompletenessValidator completeness = new CompletenessValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(completeness));

        LVWRChaosSimulator sim = new LVWRChaosSimulator(
                "int-seq-gap",
                scenarioConfig(FailureType.SEQUENCE_GAP, 25),
                engine::onTick);

        Thread.ofVirtual().start(sim).join(5_000);

        ValidationResult result = completeness.getResult();
        assertThat(result.getStatus()).isNotEqualTo(Status.PASS);
        assertThat((Long) result.getDetails().get("gapEventCount"))
                .as("gapEventCount must be > 0 after SEQUENCE_GAP injection")
                .isGreaterThan(0L);
        assertThat((Long) result.getDetails().get("missingSequenceCount"))
                .as("missingSequenceCount must be > 0 after SEQUENCE_GAP injection")
                .isGreaterThan(0L);
    }

    /**
     * PRICE_SPIKE → AccuracyValidator.
     *
     * Every tick has price = prevPrice × 1.14 (+14 %).
     * AccuracyValidator's large-move threshold is 10 %.
     * First appearance of each symbol has no prevPrice so it is counted valid.
     * Every subsequent appearance (trades 17–24 out of 25) triggers the large-move rule → invalid.
     * accuracyRate ≈ 17/25 = 68 % → way below the 99 % WARN threshold → status != PASS.
     */
    @Test
    void priceSpikeScenarioCausesAccuracyValidatorToFail() throws InterruptedException {
        AccuracyValidator accuracy = new AccuracyValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(accuracy));

        LVWRChaosSimulator sim = new LVWRChaosSimulator(
                "int-price-spike",
                scenarioConfig(FailureType.PRICE_SPIKE, 25),
                engine::onTick);

        Thread.ofVirtual().start(sim).join(5_000);

        ValidationResult result = accuracy.getResult();
        assertThat(result.getStatus())
                .as("AccuracyValidator must not PASS when all ticks are +14% price spikes")
                .isNotEqualTo(Status.PASS);
    }

    /**
     * STALE_TIMESTAMP → LatencyValidator.
     *
     * Every tick has exchangeTimestamp = receivedTimestamp − 5 seconds.
     * LatencyValidator records latencyMs = max(0, received − exchange) ≈ 5 000 ms per tick.
     * After 10 ticks p95 = 5 000 ms; FAIL threshold is thresholdMs × 2 = 1 000 ms.
     * p95 (5 000) ≥ 1 000 → Status.FAIL.
     */
    @Test
    void staleTimestampScenarioCausesLatencyValidatorToFail() throws InterruptedException {
        LatencyValidator latency = new LatencyValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(latency));

        LVWRChaosSimulator sim = new LVWRChaosSimulator(
                "int-stale-ts",
                scenarioConfig(FailureType.STALE_TIMESTAMP, 10),
                engine::onTick);

        Thread.ofVirtual().start(sim).join(5_000);

        ValidationResult result = latency.getResult();
        assertThat(result.getStatus())
                .as("LatencyValidator must FAIL when all ticks carry a 5-second stale timestamp")
                .isEqualTo(Status.FAIL);
        assertThat(result.getMetric())
                .as("p95 must be well above the 500 ms threshold")
                .isGreaterThan(500.0);
    }
}
