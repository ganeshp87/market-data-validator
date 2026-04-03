package com.marketdata.validator.controller;

import com.marketdata.validator.feed.FeedManager;
import com.marketdata.validator.simulator.FailureType;
import com.marketdata.validator.simulator.LVWRChaosSimulator;
import com.marketdata.validator.simulator.ScenarioConfig;
import com.marketdata.validator.simulator.SimulatorMode;
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

    public SimulatorController(FeedManager feedManager) {
        this.feedManager = feedManager;
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
        Map<String, Object> status = Map.of(
                "connectionId", connectionId,
                "running", sim.isRunning(),
                "mode", config.getMode().name(),
                "ticksSent", sim.getTicksSent(),
                "failuresInjected", sim.getFailuresInjected(),
                "ticksPerSecond", config.getTicksPerSecond(),
                "failureRate", config.getFailureRate(),
                "timestamp", Instant.now()
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
        double rate = Math.max(0.0, Math.min(1.0, newConfig.getFailureRate()));
        newConfig.setFailureRate(rate);
        sim.updateConfig(newConfig);
        return ResponseEntity.ok(Map.of(
                "status", "updated",
                "connectionId", connectionId,
                "mode", newConfig.getMode().name()
        ));
    }
}
