package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FieldCompletenessValidatorTest {

    private FieldCompletenessValidator gate;

    @BeforeEach
    void setUp() {
        gate = new FieldCompletenessValidator();
    }

    private Tick validTick(long seq) {
        return new Tick("BTCUSDT", new BigDecimal("45000"), new BigDecimal("1"),
                seq, Instant.now(), "feed-1");
    }

    @Test
    void validTickPassesGate() {
        assertThat(gate.isValid(validTick(1))).isTrue();
        assertThat(gate.getRejectedCount()).isZero();
        assertThat(gate.getTotalChecked()).isEqualTo(1);
    }

    @Test
    void nullSymbolRejected() {
        Tick tick = validTick(1);
        tick.setSymbol(null);
        assertThat(gate.isValid(tick)).isFalse();
        assertThat(gate.getRejectedCount()).isEqualTo(1);
    }

    @Test
    void blankSymbolRejected() {
        Tick tick = validTick(1);
        tick.setSymbol("  ");
        assertThat(gate.isValid(tick)).isFalse();
        assertThat(gate.getRejectedCount()).isEqualTo(1);
    }

    @Test
    void nullFeedIdRejected() {
        Tick tick = validTick(1);
        tick.setFeedId(null);
        assertThat(gate.isValid(tick)).isFalse();
        assertThat(gate.getRejectedCount()).isEqualTo(1);
    }

    @Test
    void blankFeedIdRejected() {
        Tick tick = validTick(1);
        tick.setFeedId("");
        assertThat(gate.isValid(tick)).isFalse();
        assertThat(gate.getRejectedCount()).isEqualTo(1);
    }

    @Test
    void nullExchangeTimestampRejected() {
        Tick tick = validTick(1);
        tick.setExchangeTimestamp(null);
        assertThat(gate.isValid(tick)).isFalse();
        assertThat(gate.getRejectedCount()).isEqualTo(1);
    }

    @Test
    void negativeSequenceNumRejected() {
        Tick tick = new Tick("BTCUSDT", new BigDecimal("45000"), new BigDecimal("1"),
                -1, Instant.now(), "feed-1");
        assertThat(gate.isValid(tick)).isFalse();
        assertThat(gate.getRejectedCount()).isEqualTo(1);
    }

    @Test
    void zeroSequenceNumAllowed() {
        Tick tick = new Tick("BTCUSDT", new BigDecimal("45000"), new BigDecimal("1"),
                0, Instant.now(), "feed-1");
        assertThat(gate.isValid(tick)).isTrue();
        assertThat(gate.getRejectedCount()).isZero();
    }

    @Test
    void nullPriceRejected() {
        Tick tick = validTick(1);
        tick.setPrice(null);
        assertThat(gate.isValid(tick)).isFalse();
        assertThat(gate.getRejectedCount()).isEqualTo(1);
    }

    @Test
    void multipleRejectionsAccumulate() {
        Tick bad1 = validTick(1);
        bad1.setSymbol(null);
        Tick bad2 = validTick(2);
        bad2.setPrice(null);
        Tick good = validTick(3);

        gate.isValid(bad1);
        gate.isValid(bad2);
        gate.isValid(good);

        assertThat(gate.getRejectedCount()).isEqualTo(2);
        assertThat(gate.getTotalChecked()).isEqualTo(3);
    }

    @Test
    void resetClearsCounters() {
        Tick bad = validTick(1);
        bad.setSymbol(null);
        gate.isValid(bad);
        assertThat(gate.getRejectedCount()).isEqualTo(1);

        gate.reset();

        assertThat(gate.getRejectedCount()).isZero();
        assertThat(gate.getTotalChecked()).isZero();
    }

    @Test
    void firstFailingFieldStopsChecking() {
        // Tick with null symbol AND null price — only one rejection counted
        Tick tick = validTick(1);
        tick.setSymbol(null);
        tick.setPrice(null);
        assertThat(gate.isValid(tick)).isFalse();
        assertThat(gate.getRejectedCount()).isEqualTo(1);
    }
}
