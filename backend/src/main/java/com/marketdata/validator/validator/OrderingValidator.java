package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.model.ValidationResult.Area;
import com.marketdata.validator.model.ValidationResult.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Validates that ticks arrive in timestamp order per symbol.
 * Also validates business rules: bid <= ask, volume >= 0.
 *
 * Blueprint Section 9.6:
 *   PASS: orderingRate >= 99.99%
 *   WARN: orderingRate >= 99.0%
 *   FAIL: orderingRate < 99.0%
 */
@Component
public class OrderingValidator implements Validator {

    private static final Logger log = LoggerFactory.getLogger(OrderingValidator.class);
    private static final double PASS_THRESHOLD = 99.99;
    private static final double WARN_THRESHOLD = 99.0;

    private final Map<String, Instant> lastTimestampBySymbol = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSequenceBySymbol = new ConcurrentHashMap<>();
    private final AtomicLong totalTicks = new AtomicLong(0);
    private final AtomicLong outOfOrderCount = new AtomicLong(0);
    private final AtomicLong duplicateCount = new AtomicLong(0);
    private final AtomicLong bidAskViolations = new AtomicLong(0);
    private final AtomicLong volumeViolations = new AtomicLong(0);

    private double passThreshold = PASS_THRESHOLD;
    private double warnThreshold = WARN_THRESHOLD;

    @Override
    public String getArea() {
        return Area.ORDERING.name();
    }

    @Override
    public void onTick(Tick tick) {
        // Idempotent: skip already-processed sequence numbers
        Long lastSeq = lastSequenceBySymbol.get(tick.getSymbol());
        if (lastSeq != null && tick.getSequenceNum() <= lastSeq) {
            if (tick.getSequenceNum() == lastSeq) {
                duplicateCount.incrementAndGet();
            } else {
                outOfOrderCount.incrementAndGet();
            }
            return;
        }

        totalTicks.incrementAndGet();

        // Check timestamp ordering per symbol
        Instant lastTs = lastTimestampBySymbol.get(tick.getSymbol());
        if (lastTs != null && tick.getExchangeTimestamp() != null
                && tick.getExchangeTimestamp().isBefore(lastTs)) {
            outOfOrderCount.incrementAndGet();
            log.debug("Out-of-order tick: {} seq={} ts={} < lastTs={}",
                    tick.getSymbol(), tick.getSequenceNum(),
                    tick.getExchangeTimestamp(), lastTs);
        }

        // Business rule: bid <= ask
        if (tick.getBid() != null && tick.getAsk() != null
                && tick.getBid().compareTo(tick.getAsk()) > 0) {
            bidAskViolations.incrementAndGet();
        }

        // Business rule: volume >= 0
        if (tick.getVolume() != null && tick.getVolume().compareTo(BigDecimal.ZERO) < 0) {
            volumeViolations.incrementAndGet();
        }

        // Update tracking state
        if (tick.getExchangeTimestamp() != null) {
            lastTimestampBySymbol.put(tick.getSymbol(), tick.getExchangeTimestamp());
        }
        lastSequenceBySymbol.put(tick.getSymbol(), tick.getSequenceNum());
    }

    @Override
    public ValidationResult getResult() {
        long total = totalTicks.get();
        if (total == 0) {
            return ValidationResult.pass(Area.ORDERING,
                    "No ticks processed yet", 100.0, passThreshold);
        }

        double orderingRate = 100.0 * (1.0 - (double) outOfOrderCount.get() / total);

        Status status;
        if (orderingRate >= passThreshold) {
            status = Status.PASS;
        } else if (orderingRate >= warnThreshold) {
            status = Status.WARN;
        } else {
            status = Status.FAIL;
        }

        String message = String.format("%.2f%% in order (%d/%d), bid/ask violations: %d, volume violations: %d",
                orderingRate, total - outOfOrderCount.get(), total,
                bidAskViolations.get(), volumeViolations.get());

        ValidationResult result = new ValidationResult(Area.ORDERING, status,
                message, orderingRate, passThreshold);
        result.getDetails().put("totalTicks", total);
        result.getDetails().put("outOfOrderCount", outOfOrderCount.get());
        result.getDetails().put("duplicateCount", duplicateCount.get());
        result.getDetails().put("bidAskViolations", bidAskViolations.get());
        result.getDetails().put("volumeViolations", volumeViolations.get());
        result.getDetails().put("orderingRate", orderingRate);

        return result;
    }

    @Override
    public void reset() {
        lastTimestampBySymbol.clear();
        lastSequenceBySymbol.clear();
        totalTicks.set(0);
        outOfOrderCount.set(0);
        duplicateCount.set(0);
        bidAskViolations.set(0);
        volumeViolations.set(0);
    }

    @Override
    public void configure(Map<String, Object> config) {
        if (config.containsKey("passThreshold")) {
            passThreshold = toDouble(config.get("passThreshold"), passThreshold);
        }
        if (config.containsKey("warnThreshold")) {
            warnThreshold = toDouble(config.get("warnThreshold"), warnThreshold);
        }
    }

    private static double toDouble(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(value.toString()); } catch (Exception e) { return fallback; }
    }

    // --- Visible for testing ---

    long getTotalTicks() {
        return totalTicks.get();
    }

    long getOutOfOrderCount() {
        return outOfOrderCount.get();
    }

    long getDuplicateCount() {
        return duplicateCount.get();
    }

    long getBidAskViolations() {
        return bidAskViolations.get();
    }

    long getVolumeViolations() {
        return volumeViolations.get();
    }
}
