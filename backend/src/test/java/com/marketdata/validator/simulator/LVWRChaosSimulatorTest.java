package com.marketdata.validator.simulator;

import com.marketdata.validator.model.Tick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LVWRChaosSimulatorTest {

    private ScenarioConfig config;

    @BeforeEach
    void setUp() {
        config = new ScenarioConfig();
        config.setNumTrades(50);        // small run — fast test
        config.setTicksPerSecond(1000); // don't wait long
        config.setIncludeHeartbeats(false);
    }

    @Test
    void cleanModeEmitsNoFailures() throws Exception {
        config.setMode(SimulatorMode.CLEAN);
        List<Tick> received = new ArrayList<>();
        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-1", config, received::add);

        Thread t = Thread.ofVirtual().start(sim);
        t.join(5_000);

        // In CLEAN mode the simulator may return null ticks for none — but non-null ticks have no failureType
        List<Tick> nonNull = received.stream().filter(tick -> tick != null).toList();
        assertThat(nonNull).isNotEmpty();
        nonNull.forEach(tick ->
                assertThat(tick.getFailureType()).as("CLEAN mode: failure type must be null").isNull()
        );
    }

    @Test
    void noisyModeInjectsSomeFailures() throws Exception {
        config.setMode(SimulatorMode.NOISY);
        config.setFailureRate(1.0); // inject on every tick
        config.setNumTrades(20);
        List<Tick> received = new ArrayList<>();

        // Use a deterministic ScenarioEngine (always picks PRICE_SPIKE — index 0 — and never
        // DISCONNECT which would sleep 8 s and blow past the 5 s join timeout).
        ScenarioEngine deterministicEngine = new ScenarioEngine(new java.util.Random() {
            @Override public double nextDouble() { return 0.0; }  // always < failureRate
            @Override public int nextInt(int n)  { return 0;   }  // always PRICE_SPIKE (index 0)
        });
        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-2", config, received::add, deterministicEngine);

        Thread t = Thread.ofVirtual().start(sim);
        t.join(5_000);

        long withFailure = received.stream()
                .filter(tick -> tick != null && tick.getFailureType() != null)
                .count();
        assertThat(withFailure).isGreaterThan(0);
    }

    @Test
    void scenarioModeOnlyInjectsTargetScenario() throws Exception {
        config.setMode(SimulatorMode.SCENARIO);
        config.setTargetScenario(FailureType.PRICE_SPIKE);
        config.setFailureRate(1.0);
        config.setNumTrades(20);
        List<Tick> received = new ArrayList<>();
        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-3", config, received::add);

        Thread t = Thread.ofVirtual().start(sim);
        t.join(5_000);

        List<Tick> withFailure = received.stream()
                .filter(tick -> tick != null && tick.getFailureType() != null)
                .toList();
        assertThat(withFailure).isNotEmpty();
        withFailure.forEach(tick ->
                assertThat(tick.getFailureType()).isEqualTo(FailureType.PRICE_SPIKE.name())
        );
    }

    @Test
    void priceSpikeInjectsPositiveSurge() throws Exception {
        config.setMode(SimulatorMode.SCENARIO);
        config.setTargetScenario(FailureType.PRICE_SPIKE);
        config.setFailureRate(1.0);
        config.setNumTrades(5);
        List<Tick> received = new ArrayList<>();
        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-4", config, received::add);

        Thread t = Thread.ofVirtual().start(sim);
        t.join(5_000);

        // Price spike ticks should always have a positive price
        received.stream()
                .filter(tick -> tick != null && FailureType.PRICE_SPIKE.name().equals(tick.getFailureType()))
                .forEach(tick ->
                        assertThat(tick.getPrice()).isGreaterThan(java.math.BigDecimal.ZERO)
                );
    }

    @Test
    void negativePriceInjectsNegativePrice() throws Exception {
        config.setMode(SimulatorMode.SCENARIO);
        config.setTargetScenario(FailureType.NEGATIVE_PRICE);
        config.setFailureRate(1.0);
        config.setNumTrades(5);
        List<Tick> received = new ArrayList<>();
        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-5", config, received::add);

        Thread t = Thread.ofVirtual().start(sim);
        t.join(5_000);

        List<Tick> negatives = received.stream()
                .filter(tick -> tick != null && FailureType.NEGATIVE_PRICE.name().equals(tick.getFailureType()))
                .toList();
        assertThat(negatives).isNotEmpty();
        negatives.forEach(tick ->
                assertThat(tick.getPrice()).isLessThan(java.math.BigDecimal.ZERO)
        );
    }

    @Test
    void stopHaltsEmission() throws Exception {
        config.setMode(SimulatorMode.CLEAN);
        config.setNumTrades(0); // unlimited
        config.setTicksPerSecond(10);
        List<Tick> received = new ArrayList<>();
        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-6", config, received::add);

        Thread t = Thread.ofVirtual().start(sim);
        Thread.sleep(200); // let it emit a few ticks
        sim.stop();
        t.join(2_000);

        assertThat(sim.isRunning()).isFalse();
        assertThat(sim.getTicksSent()).isGreaterThan(0);
    }

    @Test
    void ticksSentCounterIncrementsCorrectly() throws Exception {
        config.setMode(SimulatorMode.CLEAN);
        config.setNumTrades(10);
        List<Tick> received = new ArrayList<>();
        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-7", config, received::add);

        Thread t = Thread.ofVirtual().start(sim);
        t.join(5_000);

        // ticksSent should match received (non-null ticks + heartbeats)
        assertThat(sim.getTicksSent()).isEqualTo(received.size());
    }

    @Test
    void emissionOrderFollowsBlueprintPhase1() {
        // Verify the EMISSION_ORDER array matches the blueprint spec
        int[] order = LVWRChaosSimulator.EMISSION_ORDER;
        assertThat(order[0]).isEqualTo(126); // AGGR_INDEX first
        assertThat(order[1]).isEqualTo(1);   // EUR/USD second
        assertThat(order[17]).isEqualTo(17); // US_TSY_10Y last
    }

    @Test
    void updateConfigChangesMode() throws Exception {
        config.setMode(SimulatorMode.CLEAN);
        config.setNumTrades(0);
        config.setTicksPerSecond(10);

        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-8", config, tick -> {});
        Thread t = Thread.ofVirtual().start(sim);
        Thread.sleep(100);

        ScenarioConfig updated = new ScenarioConfig();
        updated.setMode(SimulatorMode.CHAOS);
        updated.setNumTrades(0);
        updated.setTicksPerSecond(10);
        sim.updateConfig(updated);

        assertThat(sim.getConfig().getMode()).isEqualTo(SimulatorMode.CHAOS);
        sim.stop();
        t.join(2_000);
    }

    @Test
    void getFailuresInjectedIsNonNegative() throws Exception {
        config.setMode(SimulatorMode.NOISY);
        config.setFailureRate(0.5);
        config.setNumTrades(30);
        List<Tick> received = new ArrayList<>();
        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-9", config, received::add);

        Thread t = Thread.ofVirtual().start(sim);
        t.join(5_000);

        assertThat(sim.getFailuresInjected()).isNotNull();
        assertThat(sim.getFailuresInjected().values().stream().mapToLong(Long::longValue).sum()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void staleTimestampTickHasExchangeBeforeReceived() throws Exception {
        config.setMode(SimulatorMode.SCENARIO);
        config.setTargetScenario(FailureType.STALE_TIMESTAMP);
        config.setFailureRate(1.0);
        config.setNumTrades(5);
        List<Tick> received = new ArrayList<>();
        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-10", config, received::add);

        Thread t = Thread.ofVirtual().start(sim);
        t.join(5_000);

        received.stream()
                .filter(tick -> tick != null && tick.getExchangeTimestamp() != null && tick.getReceivedTimestamp() != null)
                .forEach(tick ->
                        assertThat(tick.getExchangeTimestamp()).isBefore(tick.getReceivedTimestamp())
                );
    }

    @Test
    void feedIdIsSetOnAllTicks() throws Exception {
        config.setMode(SimulatorMode.CLEAN);
        config.setNumTrades(10);
        List<Tick> received = new ArrayList<>();
        LVWRChaosSimulator sim = new LVWRChaosSimulator("my-feed", config, received::add);

        Thread t = Thread.ofVirtual().start(sim);
        t.join(5_000);

        received.stream().filter(tick -> tick != null).forEach(tick ->
                assertThat(tick.getFeedId()).isEqualTo("my-feed")
        );
    }

    @Test
    void chaosModeMixesFailureTypes() throws Exception {
        config.setMode(SimulatorMode.CHAOS);
        config.setNumTrades(100);
        List<Tick> received = new ArrayList<>();
        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-chaos", config, received::add);

        Thread t = Thread.ofVirtual().start(sim);
        t.join(10_000);

        // In CHAOS mode at least some ticks must have non-null failureType
        long failures = received.stream()
                .filter(tick -> tick != null && tick.getFailureType() != null)
                .count();
        assertThat(failures).isGreaterThan(0);
    }

    @Test
    void chaosModeMixesMultipleFailureTypes() throws Exception {
        config.setMode(SimulatorMode.CHAOS);
        config.setNumTrades(200);
        List<Tick> received = new ArrayList<>();
        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-chaos2", config, received::add);

        Thread t = Thread.ofVirtual().start(sim);
        t.join(10_000);

        long distinctFailures = received.stream()
                .filter(tick -> tick != null && tick.getFailureType() != null)
                .map(Tick::getFailureType)
                .distinct()
                .count();
        assertThat(distinctFailures).isGreaterThan(1);
    }

    @Test
    void simulatorStopsGracefullyAfterNumTrades() throws Exception {
        config.setMode(SimulatorMode.CLEAN);
        config.setNumTrades(20);
        List<Tick> received = new ArrayList<>();
        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-stop", config, received::add);

        Thread t = Thread.ofVirtual().start(sim);
        t.join(10_000);
        // After numTrades exhausted, thread must terminate
        assertThat(sim.isRunning()).isFalse();
    }

    @Test
    void sequenceGapInjectsSeqJumpAboveNaturalNext() throws Exception {
        config.setMode(SimulatorMode.SCENARIO);
        config.setTargetScenario(FailureType.SEQUENCE_GAP);
        config.setFailureRate(1.0);
        config.setNumTrades(5);
        List<Tick> received = new ArrayList<>();
        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-gap", config, received::add);

        Thread t = Thread.ofVirtual().start(sim);
        t.join(5_000);

        // Each SEQUENCE_GAP tick skips 5 seqNums: the first tick for any symbol gets
        // seqNum = 6 (advance 5 + nextSeqNum() = 1, total 6) instead of natural 1.
        // This is large enough for the CompletenessValidator to detect a gap while keeping
        // missingSequenceCount proportional to the fault rate (not 500,000x inflated).
        List<Tick> gaps = received.stream()
                .filter(tick -> tick != null && FailureType.SEQUENCE_GAP.name().equals(tick.getFailureType()))
                .toList();
        assertThat(gaps).isNotEmpty();
        gaps.forEach(tick -> assertThat(tick.getSequenceNum()).isGreaterThan(1L));
    }

    /**
     * Fix 7 — scenario mode counter isolation.
     *
     * When a running simulator switches from a mode that has accumulated failure counts
     * (e.g. NOISY with STALE_TS, DUPLICATE, …) into SCENARIO mode, all failure counters
     * must be reset to zero so the FAILURES INJECTED panel only reflects faults from
     * the active SCENARIO run — not leftover counts from the previous mode.
     */
    @Test
    void switchingToScenarioModeResetsFailureCounters() throws Exception {
        // Phase 1: run NOISY at 100% rate so non-NEGATIVE_PRICE counters accumulate
        config.setMode(SimulatorMode.NOISY);
        config.setFailureRate(1.0);
        config.setNumTrades(0); // unlimited — we stop it manually
        config.setTicksPerSecond(1000);

        ScenarioEngine deterministicEngine = new ScenarioEngine(new java.util.Random() {
            @Override public double nextDouble() { return 0.0; }  // always < failureRate
            @Override public int nextInt(int n)  { return 3;   }  // always STALE_TIMESTAMP (index 3)
        });
        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-fix7", config, tick -> {}, deterministicEngine);
        Thread t = Thread.ofVirtual().start(sim);
        Thread.sleep(50); // let NOISY accumulate some STALE_TIMESTAMP counts

        assertThat(sim.getFailuresInjected().get(FailureType.STALE_TIMESTAMP.name()))
                .as("NOISY phase must inject STALE_TIMESTAMP").isGreaterThan(0L);

        // Phase 2: switch to SCENARIO / NEGATIVE_PRICE via updateConfig()
        ScenarioConfig scenarioConfig = new ScenarioConfig();
        scenarioConfig.setMode(SimulatorMode.SCENARIO);
        scenarioConfig.setTargetScenario(FailureType.NEGATIVE_PRICE);
        scenarioConfig.setFailureRate(1.0);
        scenarioConfig.setTicksPerSecond(1000);
        sim.stop();
        t.join(2_000);

        // Stop first to prevent concurrent increments, then updateConfig resets counters
        sim.updateConfig(scenarioConfig);

        // All counters must be zero after the mode switch — Fix 7 isolation guarantee
        sim.getFailuresInjected().forEach((type, count) ->
                assertThat(count).as("counter for %s must be 0 after switching to SCENARIO", type).isEqualTo(0L)
        );
    }

    @Test
    void switchingScenarioTargetResetsFailureCounters() throws Exception {
        // Phase 1: run SCENARIO / PRICE_SPIKE at 100% rate to build up PRICE_SPIKE counts
        config.setMode(SimulatorMode.SCENARIO);
        config.setTargetScenario(FailureType.PRICE_SPIKE);
        config.setFailureRate(1.0);
        config.setNumTrades(0);
        config.setTicksPerSecond(1000);

        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-fix7b", config, tick -> {});
        Thread t = Thread.ofVirtual().start(sim);
        Thread.sleep(50);

        assertThat(sim.getFailuresInjected().get(FailureType.PRICE_SPIKE.name()))
                .as("SCENARIO/PRICE_SPIKE phase must inject PRICE_SPIKE").isGreaterThan(0L);

        // Phase 2: switch target to NEGATIVE_PRICE
        ScenarioConfig changed = new ScenarioConfig();
        changed.setMode(SimulatorMode.SCENARIO);
        changed.setTargetScenario(FailureType.NEGATIVE_PRICE);
        changed.setFailureRate(1.0);
        changed.setTicksPerSecond(1000);
        sim.stop();
        t.join(2_000);

        // Stop first to prevent concurrent increments, then updateConfig resets counters
        sim.updateConfig(changed);

        // All counters must be zero — old PRICE_SPIKE counts must not leak into the new target's view
        sim.getFailuresInjected().forEach((type, count) ->
                assertThat(count).as("counter for %s must be 0 after changing SCENARIO target", type).isEqualTo(0L)
        );
    }

    @Test
    void waitForStopReturnsTrueAfterSimulatorExits() throws Exception {
        config.setMode(SimulatorMode.CLEAN);
        config.setNumTrades(0); // unlimited
        config.setTicksPerSecond(100);

        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-wfs", config, tick -> {});
        Thread t = Thread.ofVirtual().start(sim);
        Thread.sleep(50); // let it start

        assertThat(sim.isRunning()).isTrue();

        sim.stop();
        boolean stopped = sim.waitForStop(2_000);

        assertThat(stopped).as("waitForStop must return true when simulator has exited").isTrue();
        assertThat(sim.isRunning()).isFalse();
        t.join(1_000);
    }

    @Test
    void restartDoesNotLeaveOldSimulatorRunning() throws Exception {
        config.setMode(SimulatorMode.CLEAN);
        config.setNumTrades(0); // unlimited
        config.setTicksPerSecond(100);

        // Start first simulator
        LVWRChaosSimulator first = new LVWRChaosSimulator("feed-restart", config, tick -> {});
        Thread t1 = Thread.ofVirtual().start(first);
        Thread.sleep(50);
        assertThat(first.isRunning()).isTrue();

        // Simulate the FeedManager restart sequence: stop + waitForStop
        first.stop();
        boolean stopped = first.waitForStop(2_000);
        assertThat(stopped).as("old simulator must stop before new one starts").isTrue();

        // Start second simulator — old one is confirmed stopped
        LVWRChaosSimulator second = new LVWRChaosSimulator("feed-restart", config, tick -> {});
        Thread t2 = Thread.ofVirtual().start(second);
        Thread.sleep(50);

        // Only second is running; first is stopped
        assertThat(first.isRunning()).isFalse();
        assertThat(second.isRunning()).isTrue();

        second.stop();
        second.waitForStop(2_000);
        t1.join(1_000);
        t2.join(1_000);
    }

    @Test
    void stopInterruptsDisconnectSleep() throws Exception {
        // DISCONNECT failure sleeps for 8s — stop() must interrupt it quickly
        config.setMode(SimulatorMode.SCENARIO);
        config.setTargetScenario(FailureType.DISCONNECT);
        config.setFailureRate(1.0);
        config.setNumTrades(0);
        config.setTicksPerSecond(100);

        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-int", config, tick -> {});
        Thread t = Thread.ofVirtual().start(sim);
        Thread.sleep(150); // let it enter the DISCONNECT sleep

        long start = System.currentTimeMillis();
        sim.stop();
        boolean stopped = sim.waitForStop(2_000); // must return well before 8s

        assertThat(stopped).isTrue();
        assertThat(System.currentTimeMillis() - start)
                .as("stop+waitForStop must complete in under 2s, not wait out the 8s sleep")
                .isLessThan(2_000);
        t.join(1_000);
    }

    @Test
    void disconnectDurationIsReadFromConfig() throws Exception {
        // Configure a short disconnect duration so the test completes quickly
        config.setMode(SimulatorMode.SCENARIO);
        config.setTargetScenario(FailureType.DISCONNECT);
        config.setFailureRate(1.0);
        config.setNumTrades(1);          // stop after one DISCONNECT event
        config.setTicksPerSecond(100);
        config.setDisconnectDurationMs(100); // 100ms instead of 8000ms

        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-dur", config, tick -> {});
        long start = System.currentTimeMillis();
        Thread t = Thread.ofVirtual().start(sim);
        boolean stopped = sim.waitForStop(3_000);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(stopped)
                .as("simulator must complete within 3s when disconnectDurationMs=100")
                .isTrue();
        assertThat(elapsed)
                .as("simulator must not have slept the default 8s disconnect duration")
                .isLessThan(3_000);
        t.join(1_000);
    }
}
