package com.marketdata.validator.simulator;

import java.security.SecureRandom;
import java.util.Random;

/**
 * Decides which FailureType (if any) to inject for each tick, based on the current SimulatorMode.
 *
 * Blueprint QE Section 2 — ScenarioEngine:
 *   CLEAN    — never injects a failure
 *   NOISY    — random failures at configured failureRate using a uniform cycling strategy
 *   CHAOS    — ~50% failures, cycling through all 12 types deterministically
 *   SCENARIO — only targetScenario fires; all other counters must stay zero (strict isolation)
 */
public class ScenarioEngine {

    private final FailureType[] allTypes = FailureType.values();
    private final Random rng;
    private int chaosIndex = 0;

    public ScenarioEngine() {
        this(new SecureRandom());
    }

    /** Constructor with injectable RNG — used for testing. */
    public ScenarioEngine(Random rng) {
        this.rng = rng;
    }

    /**
     * Decide the failure type (or null for a clean tick) based on mode and config.
     */
    public FailureType decide(ScenarioConfig config) {
        return switch (config.getMode()) {
            case CLEAN -> null;
            case NOISY -> {
                if (rng.nextDouble() < config.getFailureRate()) {
                    // Pick a random failure type from the full catalogue
                    yield allTypes[rng.nextInt(allTypes.length)];
                }
                yield null;
            }
            case CHAOS -> {
                // Every other tick is a failure; cycle through all types deterministically
                if (rng.nextDouble() < 0.50) {
                    FailureType chosen = allTypes[chaosIndex % allTypes.length];
                    chaosIndex++;
                    yield chosen;
                }
                yield null;
            }
            case SCENARIO -> {
                // Strict isolation — only the configured targetScenario type fires
                if (config.getTargetScenario() == null) {
                    yield null;
                }
                if (rng.nextDouble() < config.getFailureRate()) {
                    yield config.getTargetScenario();
                }
                yield null;
            }
        };
    }

    /** Reset the deterministic chaos cycle counter. */
    public void reset() {
        chaosIndex = 0;
    }
}
