package com.marketdata.validator.simulator;

/**
 * Operating mode for the LVWR_T chaos simulator.
 *
 * Blueprint Section 2 — LVWR Chaos Simulator:
 *   CLEAN   — 100% normal ticks; all validators should stay green
 *   NOISY   — 90% normal + 10% random failures
 *   CHAOS   — 50% normal + 50% failures across all types
 *   SCENARIO — Only the configured targetScenario failure type fires; all others stay at zero
 */
public enum SimulatorMode {
    CLEAN,
    NOISY,
    CHAOS,
    SCENARIO
}
