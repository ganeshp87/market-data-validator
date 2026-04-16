package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.model.ValidationResult.Area;
import com.marketdata.validator.model.ValidationResult.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Verifies that subscribe/unsubscribe operations work correctly.
 *
 * Blueprint Section 9.7:
 *   1. When subscribe sent → first tick received within 5 seconds?
 *   2. When unsubscribe sent → no more ticks within 3 seconds?
 *   3. All subscribed symbols receiving data?
 *   4. No data for unsubscribed symbols?
 *
 * State tracked:
 *   - subscribedSymbols: Set<String>       — symbols we expect to receive
 *   - activeSymbols: Set<String>           — symbols with recent ticks
 *   - subscribeLatencies: Map<String, Duration>  — time from subscribe to first tick
 *   - leakyUnsubscribes: Set<String>       — still getting data after unsub
 *
 * Result:
 *   PASS: activeSymbols == subscribedSymbols AND leakyUnsubscribes.isEmpty()
 *   WARN: some subscribed symbols not yet active
 *   FAIL: leakyUnsubscribes NOT empty OR most symbols inactive
 *
 * Hybrid validator: event-driven (subscribe/unsubscribe) + tick-driven (tracking active symbols).
 */
@Component
public class SubscriptionValidator implements Validator {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionValidator.class);
    private static final long DEFAULT_SUBSCRIBE_TIMEOUT_MS = 5_000;    // 5 seconds
    private static final long DEFAULT_UNSUBSCRIBE_GRACE_MS = 3_000;    // 3 seconds
    private static final long DEFAULT_ACTIVE_THRESHOLD_MS = 30_000;    // 30 seconds — stale if no tick

    // --- Subscription state ---
    private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();
    private final Set<String> activeSymbols = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> subscribeTimeBySymbol = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastTickTimeBySymbol = new ConcurrentHashMap<>();
    private final Map<String, Long> subscribeLatencyMs = new ConcurrentHashMap<>();

    // Unsubscribe tracking
    private final Map<String, Instant> unsubscribeTimeBySymbol = new ConcurrentHashMap<>();
    private final Set<String> leakyUnsubscribes = ConcurrentHashMap.newKeySet();

    // Idempotency
    private final Map<String, Long> lastSequenceBySymbol = new ConcurrentHashMap<>();

    // Counters
    private final AtomicLong totalTicks = new AtomicLong(0);
    private final AtomicLong subscribeEvents = new AtomicLong(0);
    private final AtomicLong unsubscribeEvents = new AtomicLong(0);

    // Configurable thresholds
    private long subscribeTimeoutMs = DEFAULT_SUBSCRIBE_TIMEOUT_MS;
    private long unsubscribeGraceMs = DEFAULT_UNSUBSCRIBE_GRACE_MS;
    private long activeThresholdMs = DEFAULT_ACTIVE_THRESHOLD_MS;

    // Clock for time-dependent operations (injectable for testing)
    private Clock clock = Clock.systemUTC();

    @Override
    public String getArea() {
        return Area.SUBSCRIPTION.name();
    }

    /**
     * Track active symbols from incoming ticks.
     * Also detects leaky unsubscribes (ticks arriving after unsub).
     */
    @Override
    public void onTick(Tick tick) {
        String symbol = tick.getSymbol();

        // Idempotent: skip already-processed sequence numbers
        Long lastSeq = lastSequenceBySymbol.get(symbol);
        if (lastSeq != null && tick.getSequenceNum() <= lastSeq) {
            return;
        }

        totalTicks.incrementAndGet();
        lastSequenceBySymbol.put(symbol, tick.getSequenceNum());

        Instant now = clock.instant();
        lastTickTimeBySymbol.put(symbol, now);
        activeSymbols.add(symbol);

        // Record subscribe latency: time from subscribe event to first tick
        Instant subTime = subscribeTimeBySymbol.get(symbol);
        if (subTime != null && !subscribeLatencyMs.containsKey(symbol)) {
            long latency = Duration.between(subTime, now).toMillis();
            subscribeLatencyMs.put(symbol, latency);
            log.debug("Subscribe latency for {}: {}ms", symbol, latency);
        }

        // Leaky unsubscribe detection: tick arriving after unsubscribe
        Instant unsubTime = unsubscribeTimeBySymbol.get(symbol);
        if (unsubTime != null) {
            long msSinceUnsub = Duration.between(unsubTime, now).toMillis();
            if (msSinceUnsub > unsubscribeGraceMs) {
                // Well past the grace period — this is a leak
                leakyUnsubscribes.add(symbol);
                log.warn("Leaky unsubscribe: {} still receiving ticks {}ms after unsub",
                        symbol, msSinceUnsub);
            }
            // Within grace period, ticks are expected (in-flight messages)
        }
    }

    // --- Event-driven methods (called by FeedManager/FeedConnection) ---

    /**
     * Record that a symbol was subscribed.
     */
    public void onSubscribe(String symbol) {
        subscribedSymbols.add(symbol);
        subscribeTimeBySymbol.put(symbol, clock.instant());
        // Clear any previous unsubscribe state for this symbol
        unsubscribeTimeBySymbol.remove(symbol);
        leakyUnsubscribes.remove(symbol);
        subscribeEvents.incrementAndGet();
        log.debug("Subscribe recorded for {}", symbol);
    }

    /**
     * Record that a symbol was unsubscribed.
     */
    public void onUnsubscribe(String symbol) {
        subscribedSymbols.remove(symbol);
        unsubscribeTimeBySymbol.put(symbol, clock.instant());
        unsubscribeEvents.incrementAndGet();
        log.debug("Unsubscribe recorded for {}", symbol);
    }

    @Override
    public ValidationResult getResult() {
        int subscribed = subscribedSymbols.size();
        int active = 0;
        Instant now = clock.instant();

        // Count currently active symbols (within threshold)
        for (String symbol : subscribedSymbols) {
            Instant lastTick = lastTickTimeBySymbol.get(symbol);
            if (lastTick != null && Duration.between(lastTick, now).toMillis() < activeThresholdMs) {
                active++;
            }
        }

        // Check for timed-out subscriptions (subscribed but no tick within timeout)
        int timedOut = 0;
        for (String symbol : subscribedSymbols) {
            Instant subTime = subscribeTimeBySymbol.get(symbol);
            if (subTime != null && !subscribeLatencyMs.containsKey(symbol)) {
                long waitMs = Duration.between(subTime, now).toMillis();
                if (waitMs > subscribeTimeoutMs) {
                    timedOut++;
                }
            }
        }

        int leaky = leakyUnsubscribes.size();

        Status status;
        String message;

        if (subscribed == 0 && leaky == 0 && totalTicks.get() == 0) {
            return ValidationResult.pass(Area.SUBSCRIPTION,
                    "No subscriptions yet", 100.0, 100.0);
        }

        if (leaky > 0) {
            status = Status.FAIL;
            message = String.format("Leaky unsubscribes: %s", leakyUnsubscribes);
        } else if (subscribed > 0 && active == 0 && totalTicks.get() > 0) {
            status = Status.FAIL;
            message = String.format("All %d subscribed symbols inactive", subscribed);
        } else if (timedOut > 0 || (subscribed > 0 && active < subscribed)) {
            status = Status.WARN;
            message = String.format("Active: %d/%d subscribed, timedOut: %d",
                    active, subscribed, timedOut);
        } else {
            status = Status.PASS;
            message = String.format("All %d subscribed symbols active", subscribed);
        }

        double metric = subscribed == 0 ? 100.0 : 100.0 * active / subscribed;

        ValidationResult result = new ValidationResult(Area.SUBSCRIPTION, status,
                message, metric, 100.0);
        result.getDetails().put("subscribedSymbols", Set.copyOf(subscribedSymbols));
        result.getDetails().put("activeCount", active);
        result.getDetails().put("subscribedCount", subscribed);
        result.getDetails().put("leakyUnsubscribes", Set.copyOf(leakyUnsubscribes));
        result.getDetails().put("timedOutCount", timedOut);
        result.getDetails().put("subscribeEvents", subscribeEvents.get());
        result.getDetails().put("unsubscribeEvents", unsubscribeEvents.get());
        result.getDetails().put("totalTicks", totalTicks.get());

        return result;
    }

    @Override
    public void reset() {
        subscribedSymbols.clear();
        activeSymbols.clear();
        subscribeTimeBySymbol.clear();
        lastTickTimeBySymbol.clear();
        subscribeLatencyMs.clear();
        unsubscribeTimeBySymbol.clear();
        leakyUnsubscribes.clear();
        lastSequenceBySymbol.clear();
        totalTicks.set(0);
        subscribeEvents.set(0);
        unsubscribeEvents.set(0);
    }

    @Override
    public void configure(Map<String, Object> config) {
        if (config.containsKey("subscribeTimeoutMs")) {
            subscribeTimeoutMs = toLong(config.get("subscribeTimeoutMs"), subscribeTimeoutMs);
        }
        if (config.containsKey("unsubscribeGraceMs")) {
            unsubscribeGraceMs = toLong(config.get("unsubscribeGraceMs"), unsubscribeGraceMs);
        }
        if (config.containsKey("activeThresholdMs")) {
            activeThresholdMs = toLong(config.get("activeThresholdMs"), activeThresholdMs);
        }
    }

    private static long toLong(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(value.toString()); } catch (Exception e) { return fallback; }
    }

    // --- Accessors for testing ---

    Set<String> getSubscribedSymbols() { return Set.copyOf(subscribedSymbols); }
    Set<String> getActiveSymbols() { return Set.copyOf(activeSymbols); }
    Set<String> getLeakyUnsubscribes() { return Set.copyOf(leakyUnsubscribes); }
    Map<String, Long> getSubscribeLatencies() { return Map.copyOf(subscribeLatencyMs); }
    long getTotalTicks() { return totalTicks.get(); }
    long getSubscribeEventCount() { return subscribeEvents.get(); }
    long getUnsubscribeEventCount() { return unsubscribeEvents.get(); }
    void setClock(Clock clock) { this.clock = clock; }
}
