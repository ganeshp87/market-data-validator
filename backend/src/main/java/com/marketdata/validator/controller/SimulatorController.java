package com.marketdata.validator.controller;

import com.marketdata.validator.feed.FeedManager;
import com.marketdata.validator.simulator.FailureType;
import com.marketdata.validator.simulator.LVWRChaosSimulator;
import com.marketdata.validator.simulator.ScenarioConfig;
import com.marketdata.validator.validator.ValidatorEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the LVWR_T chaos simulator.
 *
 * Blueprint Section 3 — Simulator Endpoints:
 *   GET  /api/simulator/scenarios          — list all 12 FailureType values
 *   GET  /api/simulator/status             — status for a running simulator
 *   PUT  /api/simulator/config             — update ScenarioConfig at runtime
 */
@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {

    private final FeedManager feedManager;
    private final ValidatorEngine validatorEngine;

    public SimulatorController(FeedManager feedManager, ValidatorEngine validatorEngine) {
        this.feedManager = feedManager;
        this.validatorEngine = validatorEngine;
    }

    /**
     * GET /api/simulator/scenarios
     * Returns all 12 injectable FailureType values with name + description.
     */
    @GetMapping("/scenarios")
    public ResponseEntity<List<Map<String, String>>> getScenarios() {
        List<Map<String, String>> scenarios = Arrays.stream(FailureType.values())
                .map(ft -> Map.of(
                        "name", ft.name(),
                        "description", ft.getDescription()
                ))
                .toList();
        return ResponseEntity.ok(scenarios);
    }

    /**
     * GET /api/simulator/status?connectionId={id}
     * Returns live stats for the running simulator attached to the given connection.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(@RequestParam String connectionId) {
        LVWRChaosSimulator sim = feedManager.getSimulator(connectionId);
        if (sim == null) {
            return ResponseEntity.notFound().build();
        }
        ScenarioConfig config = sim.getConfig();
        String target = config.getTargetScenario() != null
                ? config.getTargetScenario().name() : "";
        Map<String, Object> status = Map.ofEntries(
                Map.entry("connectionId", connectionId),
                Map.entry("running", sim.isRunning()),
                Map.entry("mode", config.getMode().name()),
                Map.entry("targetScenario", target),
                Map.entry("ticksSent", sim.getTicksSent()),
                Map.entry("failuresInjected", sim.getFailuresInjected()),
                Map.entry("ticksPerSecond", config.getTicksPerSecond()),
                Map.entry("failureRate", config.getFailureRate()),
                Map.entry("timestamp", Instant.now())
        );
        return ResponseEntity.ok(status);
    }

    /**
     * PUT /api/simulator/config?connectionId={id}
     * Updates ScenarioConfig for a running simulator at runtime.
     */
    @PutMapping("/config")
    public ResponseEntity<Map<String, String>> updateConfig(
            @RequestParam String connectionId,
            @RequestBody ScenarioConfig newConfig) {
        LVWRChaosSimulator sim = feedManager.getSimulator(connectionId);
        if (sim == null) {
            return ResponseEntity.notFound().build();
        }
        // Sanitise rate so it stays in [0.0, 1.0]
        double rate = Math.clamp(newConfig.getFailureRate(), 0.0, 1.0);
        newConfig.setFailureRate(rate);

        // When the mode changes (e.g. CHAOS → CLEAN) reset all validator state so
        // stale spike-counts and throughput averages from the previous mode do not
        // contaminate the new mode's dashboard and alert thresholds (Fix 3).
        if (newConfig.getMode() != sim.getConfig().getMode()) {
            validatorEngine.reset();
        }

        sim.updateConfig(newConfig);
        return ResponseEntity.ok(Map.of(
                "status", "updated",
                "connectionId", connectionId,
                "mode", newConfig.getMode().name()
        ));
    }
}
