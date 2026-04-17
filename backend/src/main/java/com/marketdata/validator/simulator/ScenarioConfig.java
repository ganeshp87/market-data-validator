package com.marketdata.validator.simulator;

import java.time.LocalDate;

/**
 * Runtime configuration for the LVWR_T chaos simulator.
 *
 * Blueprint QE Section 2 — ScenarioConfig.
 * Mutated at runtime via PUT /api/simulator/config.
 */
public class ScenarioConfig {

    private SimulatorMode mode = SimulatorMode.CLEAN;
    private FailureType targetScenario;     // Only used in SCENARIO mode
    private double failureRate = 0.10;      // 0.0–1.0; default 10% for NOISY
    private int numTrades = 0;              // Total trade rows to generate (0 = unlimited)
    private LocalDate tradeDate = LocalDate.now();
    private boolean includeHeartbeats = true;
    private int ticksPerSecond = 50;        // Throughput throttle
    private long disconnectDurationMs = 8_000L;   // How long DISCONNECT failure pauses emission
    private long reconnectPauseDurationMs = 500L; // Brief pause per RECONNECT_STORM event

    public ScenarioConfig() { /* required for JSON deserialization */ }

    public SimulatorMode getMode() { return mode; }
    public void setMode(SimulatorMode mode) { this.mode = mode; }

    public FailureType getTargetScenario() { return targetScenario; }
    public void setTargetScenario(FailureType targetScenario) { this.targetScenario = targetScenario; }

    public double getFailureRate() { return failureRate; }
    public void setFailureRate(double failureRate) { this.failureRate = Math.clamp(failureRate, 0.0, 1.0); }

    public int getNumTrades() { return numTrades; }
    public void setNumTrades(int numTrades) { this.numTrades = numTrades; }

    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }

    public boolean isIncludeHeartbeats() { return includeHeartbeats; }
    public void setIncludeHeartbeats(boolean includeHeartbeats) { this.includeHeartbeats = includeHeartbeats; }

    public int getTicksPerSecond() { return ticksPerSecond; }
    public void setTicksPerSecond(int ticksPerSecond) { this.ticksPerSecond = Math.clamp(ticksPerSecond, 1, 1000); }

    public long getDisconnectDurationMs() { return disconnectDurationMs; }
    public void setDisconnectDurationMs(long disconnectDurationMs) { this.disconnectDurationMs = Math.max(0, disconnectDurationMs); }

    public long getReconnectPauseDurationMs() { return reconnectPauseDurationMs; }
    public void setReconnectPauseDurationMs(long reconnectPauseDurationMs) { this.reconnectPauseDurationMs = Math.max(0, reconnectPauseDurationMs); }
}
