package com.marketdata.validator.model;

import java.time.Instant;

/**
 * A point-in-time snapshot of latency percentiles.
 * Produced by LatencyValidator, consumed by SSE stream and UI's LatencyChart.
 * Immutable once created — represents a frozen measurement window.
 */
public class LatencyStats {

    private long p50Ms;
    private long p95Ms;
    private long p99Ms;
    private long maxMs;
    private long minMs;
    private double avgMs;
    private long count;
    private Instant windowStart;
    private Instant windowEnd;

    public LatencyStats() {
    }

    public LatencyStats(long p50Ms, long p95Ms, long p99Ms,
                        long maxMs, long minMs, double avgMs, long count,
                        Instant windowStart, Instant windowEnd) {
        this.p50Ms = p50Ms;
        this.p95Ms = p95Ms;
        this.p99Ms = p99Ms;
        this.maxMs = maxMs;
        this.minMs = minMs;
        this.avgMs = avgMs;
        this.count = count;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    // --- Getters and Setters ---

    public long getP50Ms() {
        return p50Ms;
    }

    public void setP50Ms(long p50Ms) {
        this.p50Ms = p50Ms;
    }

    public long getP95Ms() {
        return p95Ms;
    }

    public void setP95Ms(long p95Ms) {
        this.p95Ms = p95Ms;
    }

    public long getP99Ms() {
        return p99Ms;
    }

    public void setP99Ms(long p99Ms) {
        this.p99Ms = p99Ms;
    }

    public long getMaxMs() {
        return maxMs;
    }

    public void setMaxMs(long maxMs) {
        this.maxMs = maxMs;
    }

    public long getMinMs() {
        return minMs;
    }

    public void setMinMs(long minMs) {
        this.minMs = minMs;
    }

    public double getAvgMs() {
        return avgMs;
    }

    public void setAvgMs(double avgMs) {
        this.avgMs = avgMs;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(Instant windowStart) {
        this.windowStart = windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(Instant windowEnd) {
        this.windowEnd = windowEnd;
    }
}
