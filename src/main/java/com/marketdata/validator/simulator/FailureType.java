package com.marketdata.validator.simulator;

/**
 * Catalogue of all failure types the LVWR_T simulator can inject.
 *
 * Blueprint QE Section 2 — FailureType Enum.
 * Each entry maps to a specific validator + expected UI card colour.
 */
public enum FailureType {
    SEQUENCE_GAP("Skip 50,000–500,000 seqNums — triggers CompletenessValidator"),
    DUPLICATE_TICK("Re-emit same seqNum 2–3 times — triggers OrderingValidator"),
    OUT_OF_ORDER("seqNum lower than last seen by 5–20 — triggers OrderingValidator"),
    STALE_TIMESTAMP("exchangeTimestamp 5 seconds behind receive time — triggers LatencyValidator"),
    MALFORMED_PAYLOAD("Null tick returned from builder — triggers CompletenessValidator"),
    SYMBOL_MISMATCH("Aggregate 126 carries wrong source instrument ID — triggers StatefulValidator"),
    NEGATIVE_PRICE("price = -0.125 — triggers AccuracyValidator"),
    PRICE_SPIKE("Price moves > 14% in one tick — triggers AccuracyValidator"),
    DISCONNECT("Stop emitting ticks for ~8 seconds — triggers ThroughputValidator"),
    RECONNECT_STORM("5 disconnects within 30 simulated seconds — triggers ReconnectionValidator"),
    THROTTLE_BURST("800 ticks with the same timestamp — triggers ThroughputValidator/BackpressureQueue"),
    CUMVOL_BACKWARD("cumVol decreases from previous value — triggers StatefulValidator");

    private final String description;

    FailureType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
