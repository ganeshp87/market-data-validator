package com.marketdata.validator.validator;

import com.marketdata.validator.feed.FeedManager;
import com.marketdata.validator.model.Connection;
import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.model.ValidationResult.Area;
import com.marketdata.validator.model.ValidationResult.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Validates that each tick comes from a registered feed and carries a subscribed symbol.
 *
 * Checks:
 *   1. tick.feedId matches a known FeedManager connection ID
 *   2. tick.symbol is in the symbol list of the corresponding connection
 *
 * Result:
 *   PASS: matchRate >= 99.9%
 *   WARN: matchRate >= 95.0%
 *   FAIL: matchRate < 95.0%
 */
@Component
public class SourceValidator implements Validator {

    private static final Logger log = LoggerFactory.getLogger(SourceValidator.class);
    private static final double DEFAULT_PASS_THRESHOLD = 99.9;
    private static final double DEFAULT_WARN_THRESHOLD = 95.0;

    private final FeedManager feedManager;

    private final AtomicLong totalTicks = new AtomicLong(0);
    private final AtomicLong matchedTicks = new AtomicLong(0);
    private final AtomicLong unknownFeedCount = new AtomicLong(0);
    private final AtomicLong unknownSymbolCount = new AtomicLong(0);

    // Idempotency: per-symbol last processed sequence
    private final Map<String, Long> lastSequenceBySymbol = new ConcurrentHashMap<>();

    // Track which unknown feedIds/symbols we've seen (bounded to prevent unbounded growth)
    private final Set<String> unknownFeeds = ConcurrentHashMap.newKeySet();
    private final Set<String> unknownSymbols = ConcurrentHashMap.newKeySet();
    private static final int MAX_TRACKED_UNKNOWNS = 100;

    private double passThreshold = DEFAULT_PASS_THRESHOLD;
    private double warnThreshold = DEFAULT_WARN_THRESHOLD;

    public SourceValidator(FeedManager feedManager) {
        this.feedManager = feedManager;
    }

    /**
     * No-arg constructor for per-feed validator sets (created by ValidatorEngine).
     * These instances skip source checking (feedManager is null) and always PASS.
     */
    SourceValidator() {
        this.feedManager = null;
    }

    @Override
    public String getArea() {
        return Area.SOURCE.name();
    }

    @Override
    public void onTick(Tick tick) {
        // Idempotent: skip already-processed sequence numbers
        String symbol = tick.getFeedScopedSymbol();
        Long lastSeq = lastSequenceBySymbol.get(symbol);
        if (lastSeq != null && tick.getSequenceNum() <= lastSeq) {
            return;
        }
        lastSequenceBySymbol.put(symbol, tick.getSequenceNum());

        totalTicks.incrementAndGet();

        // Per-feed instances (no feedManager) always count as matched
        if (feedManager == null) {
            matchedTicks.incrementAndGet();
            return;
        }

        String feedId = tick.getFeedId();
        Connection conn = feedManager.getConnection(feedId);

        if (conn == null) {
            unknownFeedCount.incrementAndGet();
            if (unknownFeeds.size() < MAX_TRACKED_UNKNOWNS) {
                unknownFeeds.add(feedId);
            }
            log.debug("Tick from unknown feedId={} symbol={}", feedId, tick.getSymbol());
            return;
        }

        // Check if symbol is in the connection's subscription list
        List<String> symbols = conn.getSymbols();
        if (symbols != null && !symbols.isEmpty()
                && !symbols.contains(tick.getSymbol())) {
            unknownSymbolCount.incrementAndGet();
            if (unknownSymbols.size() < MAX_TRACKED_UNKNOWNS) {
                unknownSymbols.add(feedId + ":" + tick.getSymbol());
            }
            log.debug("Tick with unsubscribed symbol={} on feedId={}", tick.getSymbol(), feedId);
            return;
        }

        matchedTicks.incrementAndGet();
    }

    @Override
    public ValidationResult getResult() {
        long total = totalTicks.get();
        long matched = matchedTicks.get();

        if (total == 0) {
            return ValidationResult.pass(Area.SOURCE, "No ticks processed yet", 100.0, passThreshold);
        }

        double matchRate = (matched * 100.0) / total;

        Status status;
        String message;
        if (matchRate >= passThreshold) {
            status = Status.PASS;
            message = String.format("Source match rate: %.2f%%", matchRate);
        } else if (matchRate >= warnThreshold) {
            status = Status.WARN;
            message = String.format("Source match rate: %.2f%% (threshold: %.1f%%)", matchRate, passThreshold);
        } else {
            status = Status.FAIL;
            message = String.format("Source match rate: %.2f%% — unknown feeds: %d, unknown symbols: %d",
                    matchRate, unknownFeedCount.get(), unknownSymbolCount.get());
        }

        ValidationResult result = new ValidationResult(Area.SOURCE, status, message, matchRate, passThreshold);
        result.getDetails().put("totalTicks", total);
        result.getDetails().put("matchedTicks", matched);
        result.getDetails().put("unknownFeedCount", unknownFeedCount.get());
        result.getDetails().put("unknownSymbolCount", unknownSymbolCount.get());
        if (!unknownFeeds.isEmpty()) {
            result.getDetails().put("unknownFeeds", List.copyOf(unknownFeeds));
        }
        if (!unknownSymbols.isEmpty()) {
            result.getDetails().put("unknownSymbols", List.copyOf(unknownSymbols));
        }
        return result;
    }

    @Override
    public void reset() {
        totalTicks.set(0);
        matchedTicks.set(0);
        unknownFeedCount.set(0);
        unknownSymbolCount.set(0);
        lastSequenceBySymbol.clear();
        unknownFeeds.clear();
        unknownSymbols.clear();
    }

    @Override
    public void configure(Map<String, Object> config) {
        if (config.containsKey("sourcePassThreshold")) {
            passThreshold = ((Number) config.get("sourcePassThreshold")).doubleValue();
        }
        if (config.containsKey("sourceWarnThreshold")) {
            warnThreshold = ((Number) config.get("sourceWarnThreshold")).doubleValue();
        }
    }
}
