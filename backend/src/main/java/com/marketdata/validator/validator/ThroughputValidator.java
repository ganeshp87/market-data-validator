package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.model.ValidationResult.Area;
import com.marketdata.validator.model.ValidationResult.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Monitors message throughput, detects rate drops and zero-throughput stalls.
 *
 * Blueprint Section 9.5:
 *   Every 1 second:
 *     1. Count messages received in the last second
 *     2. Track rolling average (last 60 seconds)
 *     3. If current throughput < 50% of rolling average → WARN
 *     4. Track max throughput seen
 *
 * State tracked:
 *   - currentSecondCount (AtomicLong)
 *   - secondCounts: CircularBuffer<Long> (size 60)
 *   - rollingAverage: double
 *   - maxThroughput: long
 *   - dropDetected: boolean
 *
 * Result:
 *   PASS: no drop detected, throughput > 0
 *   WARN: throughput dropped > 50% from average
 *   FAIL: throughput == 0 for > 5 seconds while feed is connected
 */
@Component
public class ThroughputValidator implements Validator {

    private static final Logger log = LoggerFactory.getLogger(ThroughputValidator.class);

    // --- Configurable thresholds ---
    private static final int DEFAULT_WINDOW_SIZE = 60;         // seconds
    private static final double DEFAULT_DROP_PERCENT = 0.50;   // 50% drop = WARN
    private static final int DEFAULT_ZERO_THRESHOLD_SECS = 5;  // 0 msg/s for 5s = FAIL

    // --- Circular buffer for per-second counts ---
    // bufferLock guards secondCounts[], head, and filled: tick() (timer thread) writes
    // and reads the array to compute the rolling average; without a lock, concurrent
    // tick() calls can interleave writes and corrupt the rolling average calculation.
    private final ReentrantLock bufferLock = new ReentrantLock();
    private final long[] secondCounts;
    private int head = 0;           // Next write position
    private int filled = 0;        // How many slots are filled (up to windowSize)
    private final int windowSize;

    // --- Counters ---
    private final AtomicLong currentSecondCount = new AtomicLong(0);
    private volatile long lastSecondRate = 0;
    private volatile long maxThroughput = 0;
    private volatile double rollingAverage = 0.0;
    private volatile boolean dropDetected = false;
    private final AtomicInteger consecutiveZeroSeconds = new AtomicInteger(0);
    private volatile boolean feedConnected = false;
    private volatile boolean zeroThroughputFail = false;

    // Idempotency: skip already-processed sequence numbers per symbol
    private final Map<String, Long> lastSequenceBySymbol = new ConcurrentHashMap<>();

    // Total ticks processed (for details)
    private final AtomicLong totalTicks = new AtomicLong(0);

    // Configurable
    private double dropPercent = DEFAULT_DROP_PERCENT;
    private int zeroThresholdSecs = DEFAULT_ZERO_THRESHOLD_SECS;

    @Autowired
    public ThroughputValidator() {
        this(DEFAULT_WINDOW_SIZE);
    }

    /** Visible for testing — allows custom window size. */
    ThroughputValidator(int windowSize) {
        this.windowSize = windowSize;
        this.secondCounts = new long[windowSize];
    }

    @Override
    public String getArea() {
        return Area.THROUGHPUT.name();
    }

    /**
     * Called for every tick. Increments the current-second counter.
     * O(1) — just an atomic increment + idempotency check.
     */
    @Override
    public void onTick(Tick tick) {
        // Idempotent: skip already-processed sequence numbers
        Long lastSeq = lastSequenceBySymbol.get(tick.getSymbol());
        if (lastSeq != null && tick.getSequenceNum() <= lastSeq) {
            return;
        }

        currentSecondCount.incrementAndGet();
        totalTicks.incrementAndGet();

        lastSequenceBySymbol.put(tick.getSymbol(), tick.getSequenceNum());
    }

    /**
     * Called by external timer every 1 second to "close" the current window slot.
     * This snapshots the count, writes it into the circular buffer,
     * recalculates the rolling average, and detects drops/stalls.
     */
    public void tick() {
        long rate = currentSecondCount.getAndSet(0);
        lastSecondRate = rate;

        bufferLock.lock();
        try {
            // Write into circular buffer
            secondCounts[head] = rate;
            head = (head + 1) % windowSize;
            if (filled < windowSize) filled++;

            // Update max
            if (rate > maxThroughput) {
                maxThroughput = rate;
            }

            // Calculate rolling average
            long sum = 0;
            for (int i = 0; i < filled; i++) {
                sum += secondCounts[i];
            }
            rollingAverage = filled > 0 ? (double) sum / filled : 0.0;
        } finally {
            bufferLock.unlock();
        }

        // Drop detection: current rate < 50% of rolling average (only check after warmup)
        if (filled >= 5 && rollingAverage > 0) {
            dropDetected = rate < rollingAverage * dropPercent;
        } else {
            dropDetected = false;
        }

        // Zero throughput detection
        if (rate == 0 && feedConnected) {
            consecutiveZeroSeconds.incrementAndGet();
            if (consecutiveZeroSeconds.get() >= zeroThresholdSecs) {
                zeroThroughputFail = true;
            }
        } else {
            consecutiveZeroSeconds.set(0);
            if (rate > 0) {
                zeroThroughputFail = false;
            }
        }
    }

    /** Notify this validator that a feed is connected. */
    public void setFeedConnected(boolean connected) {
        this.feedConnected = connected;
        if (!connected) {
            consecutiveZeroSeconds.set(0);
            zeroThroughputFail = false;
        }
    }

    @Override
    public ValidationResult getResult() {
        long total = totalTicks.get();
        if (total == 0) {
            return ValidationResult.pass(Area.THROUGHPUT,
                    "No ticks processed yet", 0.0, 0.0);
        }

        Status status;
        String message;

        if (zeroThroughputFail) {
            status = Status.FAIL;
            message = String.format("Zero throughput for %d+ seconds while feed connected",
                    zeroThresholdSecs);
        } else if (dropDetected) {
            status = Status.WARN;
            message = String.format("Throughput dropped: %d msg/s (avg: %.0f msg/s)",
                    lastSecondRate, rollingAverage);
        } else {
            status = Status.PASS;
            message = String.format("%d msg/s (avg: %.0f, max: %d)",
                    lastSecondRate, rollingAverage, maxThroughput);
        }

        ValidationResult result = new ValidationResult(
                Area.THROUGHPUT, status, message, lastSecondRate, rollingAverage
        );
        result.getDetails().put("messagesPerSecond", lastSecondRate);
        result.getDetails().put("rollingAverage", rollingAverage);
        result.getDetails().put("maxThroughput", maxThroughput);
        result.getDetails().put("totalTicks", total);
        result.getDetails().put("dropDetected", dropDetected);
        result.getDetails().put("consecutiveZeroSeconds", consecutiveZeroSeconds.get());
        result.getDetails().put("windowSize", filled);

        return result;
    }

    @Override
    public void reset() {
        currentSecondCount.set(0);
        lastSecondRate = 0;
        maxThroughput = 0;
        rollingAverage = 0.0;
        dropDetected = false;
        consecutiveZeroSeconds.set(0);
        zeroThroughputFail = false;
        bufferLock.lock();
        try {
            head = 0;
            filled = 0;
            for (int i = 0; i < windowSize; i++) {
                secondCounts[i] = 0;
            }
        } finally {
            bufferLock.unlock();
        }
        lastSequenceBySymbol.clear();
        totalTicks.set(0);
    }

    @Override
    public void configure(Map<String, Object> config) {
        if (config.containsKey("dropPercent")) {
            this.dropPercent = ConfigUtils.toDouble(config.get("dropPercent"), dropPercent);
        }
        if (config.containsKey("zeroThresholdSecs")) {
            this.zeroThresholdSecs = ConfigUtils.toInt(config.get("zeroThresholdSecs"), zeroThresholdSecs);
        }
    }

    // --- Accessors for testing ---

    public long getLastSecondRate() { return lastSecondRate; }
    public double getRollingAverage() { return rollingAverage; }
    public long getMaxThroughput() { return maxThroughput; }
    public boolean isDropDetected() { return dropDetected; }
    public int getConsecutiveZeroSeconds() { return consecutiveZeroSeconds.get(); }
    public long getTotalTicks() { return totalTicks.get(); }
}
