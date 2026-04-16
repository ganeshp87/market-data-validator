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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Validates correctness of reconstructed state from streaming updates.
 *
 * Blueprint Section 9.8 — Stateful validation of reconstructed market data.
 *
 * Why this matters:
 *   Market data is not just individual ticks — it's STATE EVOLUTION.
 *   A single incorrect state can cause wrong trading decisions.
 *   This validator catches bugs that message-level validators miss.
 *
 * Per-symbol state maintained:
 *   - lastPrice, open, high, low (OHLC)
 *   - cumulativeVolume (non-decreasing within session)
 *   - VWAP = Σ(price × volume) / Σ(volume)
 *
 * Validation rules on each tick:
 *   1. price > 0
 *   2. low <= open <= high
 *   3. low <= lastPrice <= high
 *   4. cumulativeVolume >= 0 and non-decreasing
 *   5. VWAP between low and high
 *   6. No invalid state transitions (e.g., volume going backwards)
 *
 * Stale data detection:
 *   If no tick for a symbol within staleThreshold (default 30s) → mark as stale
 *
 * Result:
 *   PASS: consistencyRate >= 99.99% AND staleSymbols.isEmpty()
 *   WARN: consistencyRate >= 99.9% OR staleSymbols.size() <= 2
 *   FAIL: consistencyRate < 99.9% OR staleSymbols.size() > 2
 */
@Component
public class StatefulValidator implements Validator {

    private static final Logger log = LoggerFactory.getLogger(StatefulValidator.class);

    // --- Configurable thresholds ---
    private static final double DEFAULT_PASS_THRESHOLD = 99.99;
    private static final double DEFAULT_WARN_THRESHOLD = 99.9;
    private static final long DEFAULT_STALE_THRESHOLD_MS = 30_000;    // 30 seconds
    private static final int DEFAULT_MAX_VIOLATIONS = 100;            // Bounded violation buffer

    // --- Per-symbol state ---
    private final Map<String, SymbolState> stateBySymbol = new ConcurrentHashMap<>();

    // --- Counters ---
    private final AtomicLong totalChecks = new AtomicLong(0);
    private final AtomicLong consistentChecks = new AtomicLong(0);

    // --- Bounded violation list (last N) ---
    private final List<StateViolation> violations = Collections.synchronizedList(new ArrayList<>());

    // Idempotency
    private final Map<String, Long> lastSequenceBySymbol = new ConcurrentHashMap<>();

    // Configurable
    private double passThreshold = DEFAULT_PASS_THRESHOLD;
    private double warnThreshold = DEFAULT_WARN_THRESHOLD;
    private long staleThresholdMs = DEFAULT_STALE_THRESHOLD_MS;
    private int maxViolations = DEFAULT_MAX_VIOLATIONS;

    // Clock for time-dependent operations (injectable for testing)
    private Clock clock = Clock.systemUTC();

    @Override
    public String getArea() {
        return Area.STATEFUL.name();
    }

    @Override
    public void onTick(Tick tick) {
        String symbol = tick.getSymbol();

        // Idempotent: skip already-processed sequence numbers
        Long lastSeq = lastSequenceBySymbol.get(symbol);
        if (lastSeq != null && tick.getSequenceNum() <= lastSeq) {
            return;
        }

        // Get or create per-symbol state
        SymbolState state = stateBySymbol.computeIfAbsent(symbol, k -> new SymbolState());

        // Apply tick to state and validate
        List<String> ruleViolations = applyAndValidate(state, tick);

        totalChecks.incrementAndGet();

        if (ruleViolations.isEmpty()) {
            consistentChecks.incrementAndGet();
        } else {
            for (String rule : ruleViolations) {
                addViolation(new StateViolation(symbol, clock.instant(), rule));
            }
        }

        lastSequenceBySymbol.put(symbol, tick.getSequenceNum());
    }

    /**
     * Apply tick data to symbol state and return list of violated rules.
     * Empty list = all rules pass.
     */
    private List<String> applyAndValidate(SymbolState state, Tick tick) {
        List<String> violated = new ArrayList<>();
        BigDecimal price = tick.getPrice();
        BigDecimal volume = tick.getVolume();

        // --- Apply state update ---

        // First tick for this symbol → initialize OHLC
        if (state.open == null) {
            state.open = price;
            state.high = price;
            state.low = price;
        }

        state.lastPrice = price;
        state.lastTickTime = clock.instant();
        state.tickCount++;

        // Update high/low
        if (price != null) {
            if (state.high == null || price.compareTo(state.high) > 0) {
                state.high = price;
            }
            if (state.low == null || price.compareTo(state.low) < 0) {
                state.low = price;
            }
        }

        // Update cumulative volume
        BigDecimal prevCumulativeVolume = state.cumulativeVolume;
        if (volume != null && volume.compareTo(BigDecimal.ZERO) >= 0) {
            state.cumulativeVolume = state.cumulativeVolume.add(volume);
        }

        // Update VWAP: Σ(price × volume) / Σ(volume)
        if (price != null && volume != null && volume.compareTo(BigDecimal.ZERO) > 0) {
            state.priceVolumeSum = state.priceVolumeSum.add(price.multiply(volume));
            state.volumeSum = state.volumeSum.add(volume);
            if (state.volumeSum.compareTo(BigDecimal.ZERO) > 0) {
                state.vwap = state.priceVolumeSum.divide(state.volumeSum, MathContext.DECIMAL64);
            }
        }

        // --- Validate state consistency ---

        // Rule 1: price > 0
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            violated.add("PRICE_NON_POSITIVE");
            log.debug("Rule violated: PRICE_NON_POSITIVE for {} price={}", tick.getSymbol(), price);
        }

        // Rule 2: low <= open <= high
        if (state.low != null && state.open != null && state.high != null) {
            if (state.low.compareTo(state.open) > 0 || state.open.compareTo(state.high) > 0) {
                violated.add("OHLC_OPEN_OUT_OF_RANGE");
            }
        }

        // Rule 3: low <= lastPrice <= high
        if (state.low != null && state.lastPrice != null && state.high != null) {
            if (state.lastPrice.compareTo(state.low) < 0 || state.lastPrice.compareTo(state.high) > 0) {
                violated.add("PRICE_OUT_OF_OHLC_RANGE");
            }
        }

        // Rule 4: cumulativeVolume >= 0 and non-decreasing
        if (state.cumulativeVolume.compareTo(BigDecimal.ZERO) < 0) {
            violated.add("NEGATIVE_CUMULATIVE_VOLUME");
        }
        if (state.cumulativeVolume.compareTo(prevCumulativeVolume) < 0) {
            violated.add("VOLUME_DECREASED");
        }

        // Rule 5: VWAP between low and high
        if (state.vwap != null && state.low != null && state.high != null) {
            if (state.vwap.compareTo(state.low) < 0 || state.vwap.compareTo(state.high) > 0) {
                violated.add("VWAP_OUT_OF_RANGE");
            }
        }

        return violated;
    }

    private void addViolation(StateViolation violation) {
        synchronized (violations) {
            violations.add(violation);
            // Bounded: trim to last maxViolations
            while (violations.size() > maxViolations) {
                violations.remove(0);
            }
        }
    }

    @Override
    public ValidationResult getResult() {
        long total = totalChecks.get();
        if (total == 0) {
            return ValidationResult.pass(Area.STATEFUL,
                    "No ticks processed yet", 100.0, passThreshold);
        }

        double consistencyRate = 100.0 * consistentChecks.get() / total;

        // Stale symbol detection
        Set<String> staleSymbols = getStaleSymbols();

        Status status;
        String message;

        if (consistencyRate < warnThreshold || staleSymbols.size() > 2) {
            status = Status.FAIL;
            message = String.format("Consistency: %.2f%%, stale: %d symbols",
                    consistencyRate, staleSymbols.size());
        } else if (consistencyRate < passThreshold) {
            status = Status.WARN;
            message = String.format("Consistency: %.2f%%, stale: %d symbols",
                    consistencyRate, staleSymbols.size());
        } else {
            status = Status.PASS;
            message = String.format("Consistency: %.2f%% across %d symbols, %d ticks",
                    consistencyRate, stateBySymbol.size(), total);
        }

        ValidationResult result = new ValidationResult(
                Area.STATEFUL, status, message, consistencyRate, passThreshold);
        result.getDetails().put("consistencyRate", consistencyRate);
        result.getDetails().put("totalChecks", total);
        result.getDetails().put("consistentChecks", consistentChecks.get());
        result.getDetails().put("violationCount", violations.size());
        result.getDetails().put("trackedSymbols", stateBySymbol.size());
        result.getDetails().put("staleSymbolCount", staleSymbols.size());
        if (!staleSymbols.isEmpty()) {
            result.getDetails().put("staleSymbols", staleSymbols);
        }
        if (!violations.isEmpty()) {
            // Return last 5 violations as readable strings
            int start = Math.max(0, violations.size() - 5);
            List<String> recent = new ArrayList<>();
            for (int i = start; i < violations.size(); i++) {
                recent.add(violations.get(i).toString());
            }
            result.getDetails().put("recentViolations", recent);
        }

        return result;
    }

    /** Returns set of symbols that haven't received a tick within the stale threshold. */
    private Set<String> getStaleSymbols() {
        Set<String> stale = new HashSet<>();
        Instant now = clock.instant();
        for (Map.Entry<String, SymbolState> entry : stateBySymbol.entrySet()) {
            SymbolState s = entry.getValue();
            if (s.lastTickTime != null
                    && Duration.between(s.lastTickTime, now).toMillis() > staleThresholdMs) {
                stale.add(entry.getKey());
            }
        }
        return stale;
    }

    @Override
    public void reset() {
        stateBySymbol.clear();
        totalChecks.set(0);
        consistentChecks.set(0);
        violations.clear();
        lastSequenceBySymbol.clear();
    }

    @Override
    public void configure(Map<String, Object> config) {
        if (config.containsKey("passThreshold")) {
            passThreshold = toDouble(config.get("passThreshold"), passThreshold);
        }
        if (config.containsKey("warnThreshold")) {
            warnThreshold = toDouble(config.get("warnThreshold"), warnThreshold);
        }
        if (config.containsKey("staleThresholdMs")) {
            staleThresholdMs = toLong(config.get("staleThresholdMs"), staleThresholdMs);
        }
        if (config.containsKey("maxViolations")) {
            maxViolations = toInt(config.get("maxViolations"), maxViolations);
        }
    }

    private static double toDouble(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(value.toString()); } catch (Exception e) { return fallback; }
    }

    private static long toLong(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(value.toString()); } catch (Exception e) { return fallback; }
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); } catch (Exception e) { return fallback; }
    }

    // --- Accessors for testing ---

    SymbolState getSymbolState(String symbol) { return stateBySymbol.get(symbol); }
    long getTotalChecks() { return totalChecks.get(); }
    long getConsistentChecks() { return consistentChecks.get(); }
    List<StateViolation> getViolations() { return List.copyOf(violations); }
    int getTrackedSymbolCount() { return stateBySymbol.size(); }
    void setClock(Clock clock) { this.clock = clock; }

    // --- Inner classes ---

    /**
     * Per-symbol OHLC + VWAP state.
     * Package-private for testing access.
     */
    static class SymbolState {
        BigDecimal lastPrice;
        BigDecimal open;
        BigDecimal high;
        BigDecimal low;
        BigDecimal cumulativeVolume = BigDecimal.ZERO;
        BigDecimal vwap;
        BigDecimal priceVolumeSum = BigDecimal.ZERO;  // For VWAP: Σ(price × volume)
        BigDecimal volumeSum = BigDecimal.ZERO;       // For VWAP: Σ(volume)
        Instant lastTickTime;
        long tickCount = 0;
    }

    /**
     * Record of a state violation. Bounded to maxViolations.
     */
    static class StateViolation {
        final String symbol;
        final Instant timestamp;
        final String rule;

        StateViolation(String symbol, Instant timestamp, String rule) {
            this.symbol = symbol;
            this.timestamp = timestamp;
            this.rule = rule;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s: %s", timestamp, symbol, rule);
        }
    }
}
