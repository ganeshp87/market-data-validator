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
        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-2", config, received::add);

        Thread t = Thread.ofVirtual().start(sim);
        t.join(5_000);

        // At 100% failure rate at least some ticks should have a failureType set
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

        assertThat(sim.getFailuresInjected()).isGreaterThanOrEqualTo(0);
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
    void sequenceGapInjectsLargeSeqJump() throws Exception {
        config.setMode(SimulatorMode.SCENARIO);
        config.setTargetScenario(FailureType.SEQUENCE_GAP);
        config.setFailureRate(1.0);
        config.setNumTrades(5);
        List<Tick> received = new ArrayList<>();
        LVWRChaosSimulator sim = new LVWRChaosSimulator("feed-gap", config, received::add);

        Thread t = Thread.ofVirtual().start(sim);
        t.join(5_000);

        // Gap ticks should have seqNum >= 500,000 (skipped)
        List<Tick> gaps = received.stream()
                .filter(tick -> tick != null && FailureType.SEQUENCE_GAP.name().equals(tick.getFailureType()))
                .toList();
        assertThat(gaps).isNotEmpty();
        gaps.forEach(tick -> assertThat(tick.getSequenceNum()).isGreaterThanOrEqualTo(500_000));
    }
}
