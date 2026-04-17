package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.model.ValidationResult.Area;
import com.marketdata.validator.model.ValidationResult.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Validates that tick prices are positive, consistent, and within expected ranges.
 *
 * Blueprint Section 9.1:
 *   1. Price > 0 (reject zero/negative)
 *   2. Bid <= Ask (if both present)
 *   3. Price change < 10% from previous tick per symbol (large move detection)
 *
 * Thresholds:
 *   PASS: accuracyRate >= 99.99%
 *   WARN: accuracyRate >= 99.0%
 *   FAIL: accuracyRate < 99.0%
 */
@Component
public class AccuracyValidator implements Validator {

    private static final Logger log = LoggerFactory.getLogger(AccuracyValidator.class);
    private static final double DEFAULT_PASS_THRESHOLD = 99.99;
    private static final double DEFAULT_WARN_THRESHOLD = 99.0;
    private static final BigDecimal DEFAULT_LARGE_MOVE_PERCENT = new BigDecimal("0.10"); // 10%

    private final Map<String, BigDecimal> lastPriceBySymbol = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastTickTimeBySymbol = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSequenceBySymbol = new ConcurrentHashMap<>();
    // If the gap since the last tick for a symbol exceeds this threshold, the feed was
    // likely down and the new tick is treated as a fresh baseline (no spike comparison).
    private final AtomicLong reconnectGapMs = new AtomicLong(60_000L);
    private final AtomicLong totalTicks = new AtomicLong(0);
    private final AtomicLong validTicks = new AtomicLong(0);
    private final AtomicLong invalidPriceCount = new AtomicLong(0);
    private final AtomicLong bidAskViolations = new AtomicLong(0);
    private final AtomicLong largeMoveCount = new AtomicLong(0);

    private double passThreshold = DEFAULT_PASS_THRESHOLD;
    private double warnThreshold = DEFAULT_WARN_THRESHOLD;
    private BigDecimal largeMovePercent = DEFAULT_LARGE_MOVE_PERCENT;

    @Override
    public String getArea() {
        return Area.ACCURACY.name();
    }

    @Override
    public void onTick(Tick tick) {
        // Idempotent: skip already-processed sequence numbers
        Long lastSeq = lastSequenceBySymbol.get(tick.getSymbol());
        if (lastSeq != null && tick.getSequenceNum() <= lastSeq) {
            return;
        }

        totalTicks.incrementAndGet();
        boolean isValid = true;

        // Rule 1: Price must be positive
        if (tick.getPrice() == null || tick.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            invalidPriceCount.incrementAndGet();
            isValid = false;
            log.debug("Invalid price: {} for symbol={} seq={}",
                    tick.getPrice(), tick.getSymbol(), tick.getSequenceNum());
        }

        // Rule 2: Bid <= Ask (if both present)
        if (tick.getBid() != null && tick.getAsk() != null
                && tick.getBid().compareTo(tick.getAsk()) > 0) {
            bidAskViolations.incrementAndGet();
            isValid = false;
        }

        // Rule 3: Large move detection (> 10% change from previous tick for same symbol)
        if (tick.getPrice() != null && tick.getPrice().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal prevPrice = lastPriceBySymbol.get(tick.getSymbol());

            // If the feed was down for longer than reconnectGapMs, treat this tick as a
            // new baseline rather than comparing against a stale pre-disconnect price.
            Instant lastTime = lastTickTimeBySymbol.get(tick.getSymbol());
            if (prevPrice != null && lastTime != null && tick.getReceivedTimestamp() != null) {
                long gapMs = Duration.between(lastTime, tick.getReceivedTimestamp()).toMillis();
                if (gapMs >= reconnectGapMs.get()) {
                    prevPrice = null; // skip spike detection — treat as new baseline
                }
            }

            if (prevPrice != null && prevPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal change = tick.getPrice().subtract(prevPrice).abs();
                BigDecimal percentChange = change.divide(prevPrice, MathContext.DECIMAL64);
                if (percentChange.compareTo(largeMovePercent) > 0) {
                    largeMoveCount.incrementAndGet();
                    isValid = false;
                    log.debug("Large move: {} → {} ({}%) for symbol={}",
                            prevPrice, tick.getPrice(), percentChange, tick.getSymbol());
                }
            }
            lastPriceBySymbol.put(tick.getSymbol(), tick.getPrice());
        }

        if (tick.getReceivedTimestamp() != null) {
            lastTickTimeBySymbol.put(tick.getSymbol(), tick.getReceivedTimestamp());
        }

        if (isValid) {
            validTicks.incrementAndGet();
        }

        lastSequenceBySymbol.put(tick.getSymbol(), tick.getSequenceNum());
    }

    @Override
    public ValidationResult getResult() {
        long total = totalTicks.get();
        if (total == 0) {
            return ValidationResult.pass(Area.ACCURACY,
                    "No ticks processed yet", 100.0, passThreshold);
        }

        double accuracyRate = 100.0 * validTicks.get() / total;

        Status status;
        if (accuracyRate >= passThreshold) {
            status = Status.PASS;
        } else if (accuracyRate >= warnThreshold) {
            status = Status.WARN;
        } else {
            status = Status.FAIL;
        }

        String message = String.format(
                "%.2f%% accurate (%d/%d), invalid prices: %d, bid/ask violations: %d, large moves: %d",
                accuracyRate, validTicks.get(), total,
                invalidPriceCount.get(), bidAskViolations.get(), largeMoveCount.get());

        ValidationResult result = new ValidationResult(Area.ACCURACY, status,
                message, accuracyRate, passThreshold);
        result.getDetails().put("totalTicks", total);
        result.getDetails().put("validTicks", validTicks.get());
        result.getDetails().put("invalidPriceCount", invalidPriceCount.get());
        result.getDetails().put("bidAskViolations", bidAskViolations.get());
        result.getDetails().put("largeMoveCount", largeMoveCount.get());
        result.getDetails().put("accuracyRate", accuracyRate);

        return result;
    }

    @Override
    public void reset() {
        lastPriceBySymbol.clear();
        lastTickTimeBySymbol.clear();
        lastSequenceBySymbol.clear();
        totalTicks.set(0);
        validTicks.set(0);
        invalidPriceCount.set(0);
        bidAskViolations.set(0);
        largeMoveCount.set(0);
    }

    @Override
    public void configure(Map<String, Object> config) {
        if (config.containsKey("passThreshold")) {
            passThreshold = ConfigUtils.toDouble(config.get("passThreshold"), passThreshold);
        }
        if (config.containsKey("warnThreshold")) {
            warnThreshold = ConfigUtils.toDouble(config.get("warnThreshold"), warnThreshold);
        }
        if (config.containsKey("largeMovePercent")) {
            largeMovePercent = new BigDecimal(config.get("largeMovePercent").toString());
        }
        if (config.containsKey("reconnectGapMs")) {
            reconnectGapMs.set(ConfigUtils.toLong(config.get("reconnectGapMs"), reconnectGapMs.get()));
        }
    }

    // --- Visible for testing ---

    long getTotalTicks() {
        return totalTicks.get();
    }

    long getValidTicks() {
        return validTicks.get();
    }

    long getInvalidPriceCount() {
        return invalidPriceCount.get();
    }

    long getBidAskViolations() {
        return bidAskViolations.get();
    }

    long getLargeMoveCount() {
        return largeMoveCount.get();
    }
}
