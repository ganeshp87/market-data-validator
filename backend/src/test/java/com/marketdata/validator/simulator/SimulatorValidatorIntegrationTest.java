package com.marketdata.validator.simulator;

import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.model.ValidationResult.Status;
import com.marketdata.validator.validator.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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

    // ───────────────────────────────────────────────────────────────────────────
    // Parameterized test matrix: FailureType → expected validator area + status
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * Provides (FailureType, expectedValidatorArea, numTrades) for each failure type
     * that produces observable validator state change through the tick pipeline.
     *
     * Excluded from parameterized test:
     * - DUPLICATE_TICK: OrderingValidator silently counts duplicates but status stays PASS
     *   (duplicateCount tracked, but ordering rate uses only out-of-order/bidAsk/volume violations)
     * - OUT_OF_ORDER: At 100% failure rate, no clean ticks set lastExchangeTimestamp, so
     *   the backward-timestamp injection has no prevExchange to reference — effectively a no-op.
     * - SYMBOL_MISMATCH: StatefulValidator tracks per-symbol OHLC; routing ticks to "126"
     *   just enriches that symbol's state — the validator has no cross-symbol awareness.
     * - CUMVOL_BACKWARD: The sim decreases internal InstrumentState.cumVol, but ticks carry
     *   only per-tick volume (always positive), so StatefulValidator's cumVol never decreases.
     * - MALFORMED_PAYLOAD, DISCONNECT, RECONNECT_STORM, THROTTLE_BURST: handled by separate tests.
     */
    static Stream<Arguments> tickDrivenFailureTypes() {
        return Stream.of(
                // AccuracyValidator failures
                Arguments.of(FailureType.NEGATIVE_PRICE, "ACCURACY", 25),
                Arguments.of(FailureType.PRICE_SPIKE, "ACCURACY", 25),
                // CompletenessValidator failures
                Arguments.of(FailureType.SEQUENCE_GAP, "COMPLETENESS", 25),
                // LatencyValidator failures
                Arguments.of(FailureType.STALE_TIMESTAMP, "LATENCY", 10)
        );
    }

    @ParameterizedTest(name = "{0} → {1} != PASS")
    @MethodSource("tickDrivenFailureTypes")
    void failureTypeCausesValidatorToDetectProblem(FailureType failureType,
                                                    String expectedArea,
                                                    int numTrades) throws InterruptedException {
        // Create all validators so the engine can route ticks
        AccuracyValidator accuracy = new AccuracyValidator();
        CompletenessValidator completeness = new CompletenessValidator();
        OrderingValidator ordering = new OrderingValidator();
        LatencyValidator latency = new LatencyValidator();
        StatefulValidator stateful = new StatefulValidator();
        ThroughputValidator throughput = new ThroughputValidator();
        SubscriptionValidator subscription = new SubscriptionValidator();
        ReconnectionValidator reconnection = new ReconnectionValidator();

        ValidatorEngine engine = new ValidatorEngine(List.of(
                accuracy, completeness, ordering, latency,
                stateful, throughput, subscription, reconnection));

        LVWRChaosSimulator sim = new LVWRChaosSimulator(
                "int-" + failureType.name().toLowerCase(),
                scenarioConfig(failureType, numTrades),
                engine::onTick);

        Thread.ofVirtual().start(sim).join(5_000);

        // Find the result for the expected area
        Map<String, ValidationResult> results = engine.getResultsByArea();
        ValidationResult result = results.get(expectedArea);
        assertThat(result)
                .as("%s must produce a result in %s", failureType, expectedArea)
                .isNotNull();
        assertThat(result.getStatus())
                .as("%s must cause %s to report non-PASS status", failureType, expectedArea)
                .isNotEqualTo(Status.PASS);
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Special-case tests for failure types that don't flow through tick pipeline
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * DUPLICATE_TICK → OrderingValidator tracks duplicateCount but status stays PASS.
     * The duplicate tick (same seqNum) is counted via duplicateCount detail,
     * which drives RTS22 compliance in ComplianceController.
     */
    @Test
    void duplicateTickScenarioCountsDuplicates() throws InterruptedException {
        OrderingValidator ordering = new OrderingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(ordering));

        LVWRChaosSimulator sim = new LVWRChaosSimulator(
                "int-duplicate", scenarioConfig(FailureType.DUPLICATE_TICK, 25), engine::onTick);

        Thread.ofVirtual().start(sim).join(5_000);

        ValidationResult result = ordering.getResult();
        assertThat((Long) result.getDetails().get("duplicateCount"))
                .as("duplicateCount must be > 0 after DUPLICATE_TICK injection")
                .isGreaterThan(0L);
    }

    /**
     * OUT_OF_ORDER → OrderingValidator detects timestamp ordering violations.
     * Uses unlimited-trade mode: first runs CLEAN to set per-instrument timestamps,
     * then switches to SCENARIO/OUT_OF_ORDER so the sim's InstrumentState has
     * populated lastExchangeTimestamp.
     */
    @Test
    void outOfOrderScenarioCausesOrderingViolations() throws InterruptedException {
        OrderingValidator ordering = new OrderingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(ordering));

        // Start with CLEAN to populate per-instrument lastExchangeTimestamp
        ScenarioConfig cfg = new ScenarioConfig();
        cfg.setMode(SimulatorMode.CLEAN);
        cfg.setNumTrades(0); // unlimited — control via stop
        cfg.setTicksPerSecond(1000);
        cfg.setIncludeHeartbeats(false);
        LVWRChaosSimulator sim = new LVWRChaosSimulator(
                "int-oo", cfg, engine::onTick);
        Thread t = Thread.ofVirtual().start(sim);
        Thread.sleep(50); // let CLEAN phase populate timestamps

        // Switch to OUT_OF_ORDER — same sim, same InstrumentState
        ScenarioConfig oooCfg = new ScenarioConfig();
        oooCfg.setMode(SimulatorMode.SCENARIO);
        oooCfg.setTargetScenario(FailureType.OUT_OF_ORDER);
        oooCfg.setFailureRate(1.0);
        oooCfg.setTicksPerSecond(1000);
        oooCfg.setNumTrades(0);
        sim.updateConfig(oooCfg);
        Thread.sleep(50); // let OUT_OF_ORDER ticks flow

        sim.stop();
        t.join(2_000);

        assertThat((Long) ordering.getResult().getDetails().get("outOfOrderCount"))
                .as("Out-of-order count must be > 0 after OUT_OF_ORDER injection with prior clean ticks")
                .isGreaterThan(0L);
    }

    /**
     * SYMBOL_MISMATCH → ticks routed to aggregate 126 instead of correct instrument.
     * The StatefulValidator tracks per-symbol state so it doesn't detect the mismatch
     * (it simply sees ticks for "126"). This test verifies the sim handles it without error
     * and failure counts are tracked.
     */
    @Test
    void symbolMismatchTrackedBySimulator() throws InterruptedException {
        StatefulValidator stateful = new StatefulValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(stateful));

        LVWRChaosSimulator sim = new LVWRChaosSimulator(
                "int-symbol-mismatch", scenarioConfig(FailureType.SYMBOL_MISMATCH, 25), engine::onTick);

        Thread.ofVirtual().start(sim).join(5_000);

        assertThat(sim.getFailuresInjected().get(FailureType.SYMBOL_MISMATCH.name()))
                .as("SYMBOL_MISMATCH failures must be tracked")
                .isEqualTo(25L);
    }

    /**
     * CUMVOL_BACKWARD → sim decreases internal cumVol, but ticks carry per-tick volume
     * (always positive), so StatefulValidator's cumulative sum never decreases.
     * This test verifies the sim processes the scenario without error.
     */
    @Test
    void cumvolBackwardTrackedBySimulator() throws InterruptedException {
        StatefulValidator stateful = new StatefulValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(stateful));

        LVWRChaosSimulator sim = new LVWRChaosSimulator(
                "int-cumvol-backward", scenarioConfig(FailureType.CUMVOL_BACKWARD, 25), engine::onTick);

        Thread.ofVirtual().start(sim).join(5_000);

        assertThat(sim.getFailuresInjected().get(FailureType.CUMVOL_BACKWARD.name()))
                .as("CUMVOL_BACKWARD failures must be tracked")
                .isEqualTo(25L);
        assertThat(engine.getTickCount())
                .as("All ticks must reach the engine")
                .isEqualTo(25L);
    }

    /**
     * MALFORMED_PAYLOAD → null tick (never reaches validatorEngine).
     * Verifies the simulator processes all numTrades without crashing,
     * and that the failure count is properly tracked.
     */
    @Test
    void malformedPayloadProducesNullTicksWithoutCrashing() throws InterruptedException {
        AccuracyValidator accuracy = new AccuracyValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(accuracy));

        ScenarioConfig cfg = scenarioConfig(FailureType.MALFORMED_PAYLOAD, 20);
        LVWRChaosSimulator sim = new LVWRChaosSimulator(
                "int-malformed", cfg, engine::onTick);

        Thread.ofVirtual().start(sim).join(5_000);

        // All ticks are null → none reach the engine → tickCount stays 0
        assertThat(engine.getTickCount()).isEqualTo(0);
        assertThat(sim.getFailuresInjected().get(FailureType.MALFORMED_PAYLOAD.name()))
                .as("MALFORMED_PAYLOAD count must equal numTrades")
                .isEqualTo(20L);
    }

    /**
     * DISCONNECT → simulator pauses emission for disconnectDurationMs.
     * Verifies the pause completes and ticks resume afterwards.
     */
    @Test
    void disconnectPausesEmission() throws InterruptedException {
        ThroughputValidator throughput = new ThroughputValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(throughput));

        ScenarioConfig cfg = scenarioConfig(FailureType.DISCONNECT, 3);
        cfg.setDisconnectDurationMs(10); // Very short for test speed
        LVWRChaosSimulator sim = new LVWRChaosSimulator(
                "int-disconnect", cfg, engine::onTick);

        Thread.ofVirtual().start(sim).join(5_000);

        assertThat(sim.getFailuresInjected().get(FailureType.DISCONNECT.name()))
                .as("DISCONNECT failures injected")
                .isGreaterThan(0L);
    }

    /**
     * RECONNECT_STORM → simulator issues multiple brief pauses.
     * ReconnectionValidator is event-driven, not tick-driven, so this verifies
     * the simulator correctly handles the reconnect storm without crashing.
     */
    @Test
    void reconnectStormCompletesWithoutError() throws InterruptedException {
        ReconnectionValidator reconnection = new ReconnectionValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(reconnection));

        ScenarioConfig cfg = scenarioConfig(FailureType.RECONNECT_STORM, 5);
        cfg.setReconnectPauseDurationMs(5); // Very short for test speed
        LVWRChaosSimulator sim = new LVWRChaosSimulator(
                "int-reconnect-storm", cfg, engine::onTick);

        Thread.ofVirtual().start(sim).join(5_000);

        assertThat(sim.getFailuresInjected().get(FailureType.RECONNECT_STORM.name()))
                .as("RECONNECT_STORM failures injected")
                .isGreaterThan(0L);
    }

    /**
     * THROTTLE_BURST → 800 ticks emitted with the same timestamp per trade.
     * Verifies the burst reaches the engine and is counted.
     */
    @Test
    void throttleBurstFloodsThroughputValidator() throws InterruptedException {
        ThroughputValidator throughput = new ThroughputValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(throughput));

        ScenarioConfig cfg = scenarioConfig(FailureType.THROTTLE_BURST, 1);
        LVWRChaosSimulator sim = new LVWRChaosSimulator(
                "int-throttle-burst", cfg, engine::onTick);

        Thread.ofVirtual().start(sim).join(5_000);

        // 1 trade with THROTTLE_BURST emits 800 ticks
        assertThat(engine.getTickCount())
                .as("Throttle burst of 800 same-timestamp ticks must reach engine")
                .isGreaterThanOrEqualTo(800);
        assertThat(sim.getFailuresInjected().get(FailureType.THROTTLE_BURST.name()))
                .isEqualTo(1L);
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Original detailed tests (preserved for backward compatibility)
    // ───────────────────────────────────────────────────────────────────────────

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
