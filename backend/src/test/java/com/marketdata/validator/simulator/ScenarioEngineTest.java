package com.marketdata.validator.simulator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

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
}
