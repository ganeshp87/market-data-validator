package com.marketdata.validator.controller;

import com.marketdata.validator.feed.FeedManager;
import com.marketdata.validator.simulator.FailureType;
import com.marketdata.validator.simulator.LVWRChaosSimulator;
import com.marketdata.validator.simulator.ScenarioConfig;
import com.marketdata.validator.simulator.SimulatorMode;
import com.marketdata.validator.validator.ValidatorEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SimulatorControllerTest {

    private SimulatorController controller;
    private FeedManager feedManager;
    private ValidatorEngine validatorEngine;

    @BeforeEach
    void setUp() {
        feedManager = mock(FeedManager.class);
        validatorEngine = mock(ValidatorEngine.class);
        controller = new SimulatorController(feedManager, validatorEngine);
    }

    @Test
    void getScenariosReturnsAll12FailureTypes() {
        ResponseEntity<List<Map<String, String>>> response = controller.getScenarios();
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        List<Map<String, String>> scenarios = response.getBody();
        assertThat(scenarios).hasSize(12);
        assertThat(scenarios.stream().map(m -> m.get("name")))
                .containsExactlyInAnyOrder(
                        "SEQUENCE_GAP", "DUPLICATE_TICK", "OUT_OF_ORDER", "STALE_TIMESTAMP",
                        "MALFORMED_PAYLOAD", "SYMBOL_MISMATCH", "NEGATIVE_PRICE", "PRICE_SPIKE",
                        "DISCONNECT", "RECONNECT_STORM", "THROTTLE_BURST", "CUMVOL_BACKWARD"
                );
    }

    @Test
    void getScenariosIncludesDescription() {
        ResponseEntity<List<Map<String, String>>> response = controller.getScenarios();
        response.getBody().forEach(scenario ->
                assertThat(scenario).containsKey("description")
        );
    }

    @Test
    void getStatusReturns404WhenNoSimulatorFound() {
        when(feedManager.getSimulator("conn-1")).thenReturn(null);
        ResponseEntity<Map<String, Object>> response = controller.getStatus("conn-1");
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void getStatusReturnsLiveStats() {
        LVWRChaosSimulator sim = mock(LVWRChaosSimulator.class);
        ScenarioConfig config = new ScenarioConfig();
        config.setMode(SimulatorMode.NOISY);
        when(sim.isRunning()).thenReturn(true);
        when(sim.getTicksSent()).thenReturn(1234L);
        when(sim.getFailuresInjected()).thenReturn(java.util.Map.of("PRICE_SPIKE", 56L));
        when(sim.getConfig()).thenReturn(config);
        when(feedManager.getSimulator("conn-1")).thenReturn(sim);

        ResponseEntity<Map<String, Object>> response = controller.getStatus("conn-1");
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = response.getBody();
        assertThat(body.get("running")).isEqualTo(true);
        assertThat(body.get("ticksSent")).isEqualTo(1234L);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Long> failMap = (java.util.Map<String, Long>) body.get("failuresInjected");
        assertThat(failMap).isNotNull();
        assertThat(body.get("mode")).isEqualTo("NOISY");
    }

    @Test
    void updateConfigReturns404WhenNoSimulatorFound() {
        when(feedManager.getSimulator("conn-x")).thenReturn(null);
        ResponseEntity<Map<String, String>> response = controller.updateConfig("conn-x", new ScenarioConfig());
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void updateConfigAppliesNewConfigToSimulator() {
        LVWRChaosSimulator sim = mock(LVWRChaosSimulator.class);
        ScenarioConfig current = new ScenarioConfig();
        current.setMode(SimulatorMode.CLEAN);
        when(sim.getConfig()).thenReturn(current);
        when(feedManager.getSimulator("conn-1")).thenReturn(sim);

        ScenarioConfig newConfig = new ScenarioConfig();
        newConfig.setMode(SimulatorMode.CHAOS);
        newConfig.setFailureRate(0.25);

        ResponseEntity<Map<String, String>> response = controller.updateConfig("conn-1", newConfig);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(sim).updateConfig(newConfig);
    }

    @Test
    void updateConfigClampsFailureRateAboveOne() {
        LVWRChaosSimulator sim = mock(LVWRChaosSimulator.class);
        ScenarioConfig current = new ScenarioConfig();
        current.setMode(SimulatorMode.NOISY);
        when(sim.getConfig()).thenReturn(current);
        when(feedManager.getSimulator("conn-1")).thenReturn(sim);

        ScenarioConfig newConfig = new ScenarioConfig();
        newConfig.setMode(SimulatorMode.NOISY);
        newConfig.setFailureRate(5.0); // invalid — should be clamped to 1.0

        controller.updateConfig("conn-1", newConfig);
        verify(sim).updateConfig(argThat(c -> c.getFailureRate() <= 1.0));
    }

    @Test
    void updateConfigResetsValidatorsWhenModeChanges() {
        LVWRChaosSimulator sim = mock(LVWRChaosSimulator.class);
        ScenarioConfig current = new ScenarioConfig();
        current.setMode(SimulatorMode.CHAOS);
        when(sim.getConfig()).thenReturn(current);
        when(feedManager.getSimulator("conn-1")).thenReturn(sim);

        ScenarioConfig newConfig = new ScenarioConfig();
        newConfig.setMode(SimulatorMode.CLEAN); // mode change CHAOS → CLEAN

        controller.updateConfig("conn-1", newConfig);
        verify(validatorEngine).reset();
    }

    @Test
    void updateConfigDoesNotResetValidatorsWhenModeUnchanged() {
        LVWRChaosSimulator sim = mock(LVWRChaosSimulator.class);
        ScenarioConfig current = new ScenarioConfig();
        current.setMode(SimulatorMode.NOISY);
        when(sim.getConfig()).thenReturn(current);
        when(feedManager.getSimulator("conn-1")).thenReturn(sim);

        ScenarioConfig newConfig = new ScenarioConfig();
        newConfig.setMode(SimulatorMode.NOISY); // same mode — no reset expected
        newConfig.setFailureRate(0.5);

        controller.updateConfig("conn-1", newConfig);
        verify(validatorEngine, never()).reset();
    }
}
