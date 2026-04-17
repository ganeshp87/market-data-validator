package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Pre-validation gate that rejects ticks with missing or invalid required fields
 * before they are fanned out to the 9 validators.
 *
 * Rejection criteria:
 *   - symbol is null or blank
 *   - feedId is null or blank
 *   - exchangeTimestamp is null
 *   - sequenceNum < 0
 *   - price is null
 *
 * Rejected ticks are counted but not forwarded to the validator pipeline.
 */
@Component
public class FieldCompletenessValidator {

    private static final Logger log = LoggerFactory.getLogger(FieldCompletenessValidator.class);

    private final AtomicLong rejectedCount = new AtomicLong(0);
    private final AtomicLong totalChecked = new AtomicLong(0);

    /**
     * Check whether a tick has all required fields populated.
     *
     * @return true if the tick is valid and should proceed to validators; false if rejected.
     */
    public boolean isValid(Tick tick) {
        totalChecked.incrementAndGet();

        if (tick.getSymbol() == null || tick.getSymbol().isBlank()) {
            rejectedCount.incrementAndGet();
            log.debug("Rejected tick: null/blank symbol, seq={}", tick.getSequenceNum());
            return false;
        }
        if (tick.getFeedId() == null || tick.getFeedId().isBlank()) {
            rejectedCount.incrementAndGet();
            log.debug("Rejected tick: null/blank feedId, symbol={}", tick.getSymbol());
            return false;
        }
        if (tick.getExchangeTimestamp() == null) {
            rejectedCount.incrementAndGet();
            log.debug("Rejected tick: null exchangeTimestamp, symbol={} seq={}", tick.getSymbol(), tick.getSequenceNum());
            return false;
        }
        if (tick.getSequenceNum() < 0) {
            rejectedCount.incrementAndGet();
            log.debug("Rejected tick: negative seqNum={}, symbol={}", tick.getSequenceNum(), tick.getSymbol());
            return false;
        }
        if (tick.getPrice() == null) {
            rejectedCount.incrementAndGet();
            log.debug("Rejected tick: null price, symbol={} seq={}", tick.getSymbol(), tick.getSequenceNum());
            return false;
        }
        return true;
    }

    public long getRejectedCount() {
        return rejectedCount.get();
    }

    public long getTotalChecked() {
        return totalChecked.get();
    }

    public void reset() {
        rejectedCount.set(0);
        totalChecked.set(0);
    }
}
