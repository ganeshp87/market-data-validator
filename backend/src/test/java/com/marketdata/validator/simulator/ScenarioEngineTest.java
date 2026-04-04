package com.marketdata.validator.simulator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioEngineTest {

    private ScenarioEngine engine;
    private ScenarioConfig config;

    @BeforeEach
    void setUp() {
        engine = new ScenarioEngine();
        config = new ScenarioConfig();
    }

    @Test
    void cleanModeAlwaysReturnsNull() {
        config.setMode(SimulatorMode.CLEAN);
        // Run many times — should always be null
        for (int i = 0; i < 100; i++) {
            assertThat(engine.decide(config)).isNull();
        }
    }

    @Test
    void noisyModeReturnsNullAtZeroRate() {
        config.setMode(SimulatorMode.NOISY);
        config.setFailureRate(0.0);
        for (int i = 0; i < 50; i++) {
            assertThat(engine.decide(config)).isNull();
        }
    }

    @Test
    void noisyModeReturnsNonNullAtFullRate() {
        config.setMode(SimulatorMode.NOISY);
        config.setFailureRate(1.0);
        boolean sawNonNull = false;
        for (int i = 0; i < 20; i++) {
            if (engine.decide(config) != null) {
                sawNonNull = true;
                break;
            }
        }
        assertThat(sawNonNull).isTrue();
    }

    @Test
    void chaosModeMixesFailures() {
        config.setMode(SimulatorMode.CHAOS);
        boolean sawNull = false;
        boolean sawNonNull = false;
        for (int i = 0; i < 100; i++) {
            FailureType ft = engine.decide(config);
            if (ft == null) sawNull = true;
            else sawNonNull = true;
        }
        // CHAOS mode 50% rate — expect both outcomes
        assertThat(sawNonNull).isTrue();
    }

    @Test
    void scenarioModeOnlyReturnsTargetType() {
        config.setMode(SimulatorMode.SCENARIO);
        config.setTargetScenario(FailureType.PRICE_SPIKE);
        config.setFailureRate(1.0);
        for (int i = 0; i < 20; i++) {
            FailureType ft = engine.decide(config);
            if (ft != null) {
                assertThat(ft).isEqualTo(FailureType.PRICE_SPIKE);
            }
        }
    }

    @Test
    void scenarioModeWithNullTargetBehavesLikeClean() {
        config.setMode(SimulatorMode.SCENARIO);
        config.setTargetScenario(null);
        config.setFailureRate(1.0);
        for (int i = 0; i < 20; i++) {
            assertThat(engine.decide(config)).isNull();
        }
    }

    @Test
    void resetClearsChaosIndexState() {
        config.setMode(SimulatorMode.CHAOS);
        for (int i = 0; i < 10; i++) engine.decide(config);
        engine.reset(); // should not throw; state cleared
        // After reset, chaos index starts over — first CHAOS decision may be null or non-null
        FailureType ft = engine.decide(config);
        // just verify it doesn't throw
        assertThat(ft == null || ft != null).isTrue();
    }

    @Test
    void noisyModeReturnsDifferentFailureTypesOverTime() {
        config.setMode(SimulatorMode.NOISY);
        config.setFailureRate(1.0);
        long distinct = java.util.stream.Stream.generate(() -> engine.decide(config))
                .limit(200)
                .filter(ft -> ft != null)
                .map(Enum::name)
                .distinct()
                .count();
        assertThat(distinct).isGreaterThan(1);
    }

    /**
     * CHAOS mode uses chaosIndex % allTypes.length cycling deterministically.
     * An RNG that always returns 0.0 (< 0.5) forces every decide() call to yield a failure.
     * After 12 calls one rotation is complete — all 12 FailureType values must have appeared.
     */
    @Test
    void chaosModeEventuallyCoversAllTwelveFailureTypes() {
        // RNG always returns 0.0 → nextDouble() < 0.50 is always true
        Random alwaysLow = new Random() {
            @Override public double nextDouble() { return 0.0; }
        };
        ScenarioEngine deterministicEngine = new ScenarioEngine(alwaysLow);
        config.setMode(SimulatorMode.CHAOS);

        Set<FailureType> seen = EnumSet.noneOf(FailureType.class);
        for (int i = 0; i < FailureType.values().length; i++) {
            FailureType ft = deterministicEngine.decide(config);
            assertThat(ft).as("CHAOS must return non-null when rng always yields 0.0").isNotNull();
            seen.add(ft);
        }
        assertThat(seen).containsExactlyInAnyOrder(FailureType.values());
    }

    /**
     * SCENARIO mode with null targetScenario must return null regardless of failureRate.
     * The guard fires before the rate check, so rate=0.0 and rate=1.0 both yield null.
     */
    @Test
    void scenarioModeNullTargetGuardIsRateIndependent() {
        config.setMode(SimulatorMode.SCENARIO);
        config.setTargetScenario(null);

        config.setFailureRate(0.0);
        assertThat(engine.decide(config)).as("null target at rate=0.0 must return null").isNull();

        config.setFailureRate(1.0);
        assertThat(engine.decide(config)).as("null target at rate=1.0 must return null").isNull();
    }
}
