package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.model.ValidationResult.Area;
import com.marketdata.validator.model.ValidationResult.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects missed/dropped messages by tracking sequence gaps and stale symbols.
 *
 * Blueprint Section 9.3:
 *   1. Track last sequence number per symbol
 *   2. If current.seqNum != last.seqNum + 1 → gap detected
 *   3. If time since last tick > heartbeatThreshold (10s) → stale
 *
 * Result thresholds:
 *   PASS: completenessRate >= 99.99% AND staleSymbols.isEmpty()
 *   WARN: completenessRate >= 99.0%
 *   FAIL: completenessRate < 99.0% OR staleSymbols.size() > 0
 */
@Component
public class CompletenessValidator implements Validator {

    private static final Logger log = LoggerFactory.getLogger(CompletenessValidator.class);
    private static final long DEFAULT_HEARTBEAT_THRESHOLD_MS = 10_000; // 10 seconds
    private static final double DEFAULT_PASS_THRESHOLD = 99.99;
    private static final double DEFAULT_WARN_THRESHOLD = 99.0;
    // Hysteresis: a symbol must receive consistent ticks for this long before being
    // removed from staleSymbols. Prevents FAIL→PASS→FAIL oscillation on unstable feeds.
    private static final long DEFAULT_STALE_RECOVERY_WINDOW_MS = 30_000L; // 30 seconds

    private final Map<String, Long> lastSeqBySymbol = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastTickTimeBySymbol = new ConcurrentHashMap<>();
    private final Set<String> staleSymbols = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> staleRecoveryStartBySymbol = new ConcurrentHashMap<>();
    private final AtomicLong totalTicks = new AtomicLong(0);
    private final AtomicLong gapEventCount = new AtomicLong(0);
    private final AtomicLong missingSequenceCount = new AtomicLong(0);

    private long heartbeatThresholdMs = DEFAULT_HEARTBEAT_THRESHOLD_MS;
    private long staleRecoveryWindowMs = DEFAULT_STALE_RECOVERY_WINDOW_MS;

    @Override
    public String getArea() {
        return Area.COMPLETENESS.name();
    }

    @Override
    public void onTick(Tick tick) {
        // Idempotent: skip already-processed sequence numbers
        Long lastSeq = lastSeqBySymbol.get(tick.getSymbol());
        if (lastSeq != null && tick.getSequenceNum() <= lastSeq) {
            return;
        }

        totalTicks.incrementAndGet();

        // Gap detection: seqNum should be lastSeq + 1
        if (lastSeq != null && tick.getSequenceNum() != lastSeq + 1) {
            long missedCount = tick.getSequenceNum() - lastSeq - 1;
            gapEventCount.incrementAndGet();
            missingSequenceCount.addAndGet(Math.max(0, missedCount));
            log.debug("Sequence gap: symbol={} expected={} got={} missed={}",
                    tick.getSymbol(), lastSeq + 1, tick.getSequenceNum(), missedCount);
        }

        // Staleness detection: time since last tick
        Instant lastTime = lastTickTimeBySymbol.get(tick.getSymbol());
        if (lastTime != null && tick.getReceivedTimestamp() != null) {
            long gapMs = Duration.between(lastTime, tick.getReceivedTimestamp()).toMillis();
            if (gapMs > heartbeatThresholdMs) {
                staleSymbols.add(tick.getSymbol());
                staleRecoveryStartBySymbol.remove(tick.getSymbol()); // reset recovery timer
                log.debug("Stale symbol detected: {} gap={}ms threshold={}ms",
                        tick.getSymbol(), gapMs, heartbeatThresholdMs);
            } else if (staleSymbols.contains(tick.getSymbol())) {
                // Hysteresis: only remove from staleSymbols after staleRecoveryWindowMs of
                // consistent ticks — prevents oscillation on unstable feeds.
                Instant recoveryStart = staleRecoveryStartBySymbol.computeIfAbsent(
                        tick.getSymbol(), k -> tick.getReceivedTimestamp());
                long recoveredMs = Duration.between(recoveryStart, tick.getReceivedTimestamp()).toMillis();
                if (recoveredMs >= staleRecoveryWindowMs) {
                    staleSymbols.remove(tick.getSymbol());
                    staleRecoveryStartBySymbol.remove(tick.getSymbol());
                    log.debug("Stale symbol recovered after {}ms: {}", recoveredMs, tick.getSymbol());
                }
            }
        }

        // Update tracking state
        lastSeqBySymbol.put(tick.getSymbol(), tick.getSequenceNum());
        if (tick.getReceivedTimestamp() != null) {
            lastTickTimeBySymbol.put(tick.getSymbol(), tick.getReceivedTimestamp());
        }
    }

    @Override
    public ValidationResult getResult() {
        if (totalTicks.get() == 0) {
            return ValidationResult.pass(Area.COMPLETENESS,
                    "No ticks processed yet", 100.0, DEFAULT_PASS_THRESHOLD);
        }

        long gapEvents = gapEventCount.get();
        long missingSeqs = missingSequenceCount.get();
        long total = totalTicks.get();
        int stale = staleSymbols.size();

        // Completeness rate: expected ticks vs actual
        // totalTicks = ticks received; ticks + missingSeqs = expected
        double completenessRate = 100.0 * total / (total + missingSeqs);

        Status status;
        if (stale > 0 || completenessRate < DEFAULT_WARN_THRESHOLD) {
            status = Status.FAIL;
        } else if (completenessRate < DEFAULT_PASS_THRESHOLD) {
            status = Status.WARN;
        } else {
            status = Status.PASS;
        }

        String message = String.format(
                "gapEvents=%d, missingSeqs=%d, stale symbols=%d (%s), total ticks=%d",
                gapEvents, missingSeqs, stale, staleSymbols, total);

        ValidationResult result = new ValidationResult(Area.COMPLETENESS, status,
                message, completenessRate, DEFAULT_PASS_THRESHOLD);
        result.getDetails().put("gapEventCount", gapEvents);
        result.getDetails().put("missingSequenceCount", missingSeqs);
        result.getDetails().put("completenessRate", completenessRate);
        result.getDetails().put("staleSymbolCount", stale);
        result.getDetails().put("staleSymbols", Set.copyOf(staleSymbols));
        result.getDetails().put("totalTicks", total);
        result.getDetails().put("trackedSymbols", lastSeqBySymbol.size());

        return result;
    }

    @Override
    public void reset() {
        lastSeqBySymbol.clear();
        lastTickTimeBySymbol.clear();
        staleSymbols.clear();
        staleRecoveryStartBySymbol.clear();
        totalTicks.set(0);
        gapEventCount.set(0);
        missingSequenceCount.set(0);
    }

    @Override
    public void configure(Map<String, Object> config) {
        if (config.containsKey("heartbeatThresholdMs")) {
            heartbeatThresholdMs = ConfigUtils.toLong(config.get("heartbeatThresholdMs"), heartbeatThresholdMs);
        }
        if (config.containsKey("staleRecoveryWindowMs")) {
            staleRecoveryWindowMs = ConfigUtils.toLong(config.get("staleRecoveryWindowMs"), staleRecoveryWindowMs);
        }
    }

    // --- Visible for testing ---

    long getTotalTicks() {
        return totalTicks.get();
    }

    long getGapEventCount() {
        return gapEventCount.get();
    }

    long getMissingSequenceCount() {
        return missingSequenceCount.get();
    }

    Set<String> getStaleSymbols() {
        return Set.copyOf(staleSymbols);
    }

    int getTrackedSymbolCount() {
        return lastSeqBySymbol.size();
    }
}
