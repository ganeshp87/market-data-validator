package com.marketdata.validator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Startup configuration for validator thresholds.
 * Values are read from application.properties at startup and applied to the
 * ValidatorEngine before any connections are loaded. The runtime
 * PUT /api/validation/config API can override these values at any time.
 */
@Component
@ConfigurationProperties(prefix = "validator")
public class ValidatorProperties {

    private Latency latency = new Latency();
    private Accuracy accuracy = new Accuracy();
    private Completeness completeness = new Completeness();
    private Throughput throughput = new Throughput();

    public Latency getLatency() { return latency; }
    public void setLatency(Latency latency) { this.latency = latency; }

    public Accuracy getAccuracy() { return accuracy; }
    public void setAccuracy(Accuracy accuracy) { this.accuracy = accuracy; }

    public Completeness getCompleteness() { return completeness; }
    public void setCompleteness(Completeness completeness) { this.completeness = completeness; }

    public Throughput getThroughput() { return throughput; }
    public void setThroughput(Throughput throughput) { this.throughput = throughput; }

    public static class Latency {
        /** p95 threshold above which status becomes WARN (ms). Default: 500. */
        private long warnThresholdMs = 500;
        public long getWarnThresholdMs() { return warnThresholdMs; }
        public void setWarnThresholdMs(long warnThresholdMs) { this.warnThresholdMs = warnThresholdMs; }
    }

    public static class Accuracy {
        /** Price spike threshold as a percentage (0–100). Default: 10 (= 10%). */
        private double spikePctThreshold = 10.0;
        public double getSpikePctThreshold() { return spikePctThreshold; }
        public void setSpikePctThreshold(double spikePctThreshold) { this.spikePctThreshold = spikePctThreshold; }
    }

    public static class Completeness {
        /** Gap between ticks above which a symbol is considered stale (ms). Default: 10000. */
        private long staleThresholdMs = 10_000;
        public long getStaleThresholdMs() { return staleThresholdMs; }
        public void setStaleThresholdMs(long staleThresholdMs) { this.staleThresholdMs = staleThresholdMs; }
    }

    public static class Throughput {
        /** Consecutive seconds of zero messages before FAIL is triggered. Default: 5. */
        private int minMessagesPerSecond = 5;
        public int getMinMessagesPerSecond() { return minMessagesPerSecond; }
        public void setMinMessagesPerSecond(int minMessagesPerSecond) { this.minMessagesPerSecond = minMessagesPerSecond; }
    }
}
