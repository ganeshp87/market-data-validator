package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.model.ValidationResult.Area;
import com.marketdata.validator.model.ValidationResult.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tracks end-to-end latency percentiles with spike detection.
 *
 * Blueprint Section 9.2:
 *   - Circular buffer of last 10,000 latency samples
 *   - Percentile calculation: p50, p95, p99, min, max
 *   - Spike detection: latency > p99 * 3
 *
 * Result thresholds:
 *   PASS: p95 < threshold (100ms) AND spikeCount < 5
 *   WARN: p95 < threshold * 2 OR spikeCount >= 5
 *   FAIL: p95 >= threshold * 2 OR spikeCount >= 20
 */
@Component
public class LatencyValidator implements Validator {

    private static final Logger log = LoggerFactory.getLogger(LatencyValidator.class);
    private static final int DEFAULT_BUFFER_SIZE = 10_000;
    private static final long DEFAULT_THRESHOLD_MS = 100;
    private static final int SPIKE_WARN_THRESHOLD = 5;
    private static final int SPIKE_FAIL_THRESHOLD = 20;

    // Circular buffer for O(1) insertion, bounded memory
    private long[] buffer;
    private int writeIndex;
    private int count;
    private final ReentrantLock bufferLock = new ReentrantLock();

    // Per-symbol idempotency
    private final Map<String, Long> lastSequenceBySymbol = new ConcurrentHashMap<>();

    // Aggregate counters
    private final AtomicLong totalTicks = new AtomicLong(0);
    private final AtomicLong spikeCount = new AtomicLong(0);
    private long minLatency = Long.MAX_VALUE;
    private long maxLatency = Long.MIN_VALUE;

    // Configurable
    private long thresholdMs = DEFAULT_THRESHOLD_MS;
    private int bufferSize = DEFAULT_BUFFER_SIZE;

    public LatencyValidator() {
        this.buffer = new long[DEFAULT_BUFFER_SIZE];
        this.writeIndex = 0;
        this.count = 0;
    }

    @Override
    public String getArea() {
        return Area.LATENCY.name();
    }

    @Override
    public void onTick(Tick tick) {
        // Idempotent: skip already-processed sequence numbers
        Long lastSeq = lastSequenceBySymbol.get(tick.getSymbol());
        if (lastSeq != null && tick.getSequenceNum() <= lastSeq) {
            return;
        }

        Duration latency = tick.getLatency();
        long latencyMs = latency.toMillis();

        totalTicks.incrementAndGet();

        bufferLock.lock();
        try {
            // Add to circular buffer
            buffer[writeIndex] = latencyMs;
            writeIndex = (writeIndex + 1) % bufferSize;
            if (count < bufferSize) {
                count++;
            }

            // Track min/max
            if (latencyMs < minLatency) {
                minLatency = latencyMs;
            }
            if (latencyMs > maxLatency) {
                maxLatency = latencyMs;
            }

            // Spike detection: latency > p99 * 3
            if (count >= 100) { // Need minimum samples for meaningful p99
                long currentP99 = calculatePercentile(99);
                if (latencyMs > currentP99 * 3) {
                    spikeCount.incrementAndGet();
                    log.debug("Latency spike: {}ms > 3×p99({}ms) for symbol={}",
                            latencyMs, currentP99, tick.getSymbol());
                }
            }
        } finally {
            bufferLock.unlock();
        }

        lastSequenceBySymbol.put(tick.getSymbol(), tick.getSequenceNum());
    }

    @Override
    public ValidationResult getResult() {
        if (totalTicks.get() == 0) {
            return ValidationResult.pass(Area.LATENCY,
                    "No ticks processed yet", 0.0, thresholdMs);
        }

        long p50, p95, p99;
        long min, max;
        int currentCount;

        bufferLock.lock();
        try {
            p50 = calculatePercentile(50);
            p95 = calculatePercentile(95);
            p99 = calculatePercentile(99);
            min = minLatency;
            max = maxLatency;
            currentCount = count;
        } finally {
            bufferLock.unlock();
        }

        long spikes = spikeCount.get();

        Status status;
        if (p95 >= thresholdMs * 2 || spikes >= SPIKE_FAIL_THRESHOLD) {
            status = Status.FAIL;
        } else if (p95 >= thresholdMs || spikes >= SPIKE_WARN_THRESHOLD) {
            status = Status.WARN;
        } else {
            status = Status.PASS;
        }

        String message = String.format(
                "p50=%dms p95=%dms p99=%dms min=%dms max=%dms spikes=%d (threshold=%dms, samples=%d)",
                p50, p95, p99, min, max, spikes, thresholdMs, currentCount);

        ValidationResult result = new ValidationResult(Area.LATENCY, status,
                message, p95, thresholdMs);
        result.getDetails().put("p50", p50);
        result.getDetails().put("p95", p95);
        result.getDetails().put("p99", p99);
        result.getDetails().put("min", min);
        result.getDetails().put("max", max);
        result.getDetails().put("spikeCount", spikes);
        result.getDetails().put("totalTicks", totalTicks.get());
        result.getDetails().put("bufferSamples", currentCount);

        return result;
    }

    @Override
    public void reset() {
        bufferLock.lock();
        try {
            buffer = new long[bufferSize];
            writeIndex = 0;
            count = 0;
            minLatency = Long.MAX_VALUE;
            maxLatency = Long.MIN_VALUE;
        } finally {
            bufferLock.unlock();
        }
        lastSequenceBySymbol.clear();
        totalTicks.set(0);
        spikeCount.set(0);
    }

    @Override
    public void configure(Map<String, Object> config) {
        if (config.containsKey("thresholdMs")) {
            thresholdMs = ((Number) config.get("thresholdMs")).longValue();
        }
        if (config.containsKey("bufferSize")) {
            bufferSize = ((Number) config.get("bufferSize")).intValue();
            bufferLock.lock();
            try {
                buffer = new long[bufferSize];
                writeIndex = 0;
                count = 0;
            } finally {
                bufferLock.unlock();
            }
        }
    }

    /**
     * Calculates the given percentile from the circular buffer.
     * Must be called while holding bufferLock.
     *
     * Copies only the filled portion of the buffer, sorts it,
     * and returns the value at the percentile index.
     */
    private long calculatePercentile(int percentile) {
        if (count == 0) {
            return 0;
        }

        long[] sorted = new long[count];
        if (count < bufferSize) {
            System.arraycopy(buffer, 0, sorted, 0, count);
        } else {
            System.arraycopy(buffer, 0, sorted, 0, bufferSize);
        }
        Arrays.sort(sorted);

        int index = (int) Math.ceil(percentile / 100.0 * count) - 1;
        if (index < 0) index = 0;
        if (index >= count) index = count - 1;
        return sorted[index];
    }

    // --- Visible for testing ---

    long getTotalTicks() {
        return totalTicks.get();
    }

    long getSpikeCount() {
        return spikeCount.get();
    }

    long getP50() {
        bufferLock.lock();
        try {
            return calculatePercentile(50);
        } finally {
            bufferLock.unlock();
        }
    }

    long getP95() {
        bufferLock.lock();
        try {
            return calculatePercentile(95);
        } finally {
            bufferLock.unlock();
        }
    }

    long getP99() {
        bufferLock.lock();
        try {
            return calculatePercentile(99);
        } finally {
            bufferLock.unlock();
        }
    }

    long getMinLatency() {
        bufferLock.lock();
        try {
            return minLatency;
        } finally {
            bufferLock.unlock();
        }
    }

    long getMaxLatency() {
        bufferLock.lock();
        try {
            return maxLatency;
        } finally {
            bufferLock.unlock();
        }
    }

    int getBufferCount() {
        bufferLock.lock();
        try {
            return count;
        } finally {
            bufferLock.unlock();
        }
    }
}
