package com.marketdata.validator.validator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A Clock that can be manually advanced for deterministic testing.
 * Eliminates Thread.sleep-based flakiness in time-dependent validator tests.
 */
class ManualClock extends Clock {

    private Instant instant;
    private final ZoneId zone = ZoneId.of("UTC");

    ManualClock(Instant initial) {
        this.instant = initial;
    }

    void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
