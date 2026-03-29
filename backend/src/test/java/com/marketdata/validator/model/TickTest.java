package com.marketdata.validator.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TickTest {

    @Test
    void constructorSetsReceivedTimestampAutomatically() {
        Instant before = Instant.now();
        Tick tick = new Tick("BTCUSDT", new BigDecimal("45123.45"),
                new BigDecimal("0.5"), 1, Instant.now(), "feed-1");
        Instant after = Instant.now();

        assertThat(tick.getReceivedTimestamp()).isBetween(before, after);
    }

    @Test
    void constructorSetsAllFields() {
        Instant exchangeTs = Instant.parse("2026-03-23T10:00:00Z");
        Tick tick = new Tick("ETHUSDT", new BigDecimal("3456.78"),
                new BigDecimal("1.5"), 9999, exchangeTs, "feed-2");

        assertThat(tick.getSymbol()).isEqualTo("ETHUSDT");
        assertThat(tick.getPrice()).isEqualByComparingTo("3456.78");
        assertThat(tick.getVolume()).isEqualByComparingTo("1.5");
        assertThat(tick.getSequenceNum()).isEqualTo(9999);
        assertThat(tick.getExchangeTimestamp()).isEqualTo(exchangeTs);
        assertThat(tick.getFeedId()).isEqualTo("feed-2");
    }

    @Test
    void bigDecimalPreservesPrecision() {
        // This is why we use BigDecimal — double would lose precision
        Tick tick = new Tick();
        tick.setPrice(new BigDecimal("45123.456789012345"));

        // With double: 45123.456789012345 → 45123.45678901234 (truncated!)
        // With BigDecimal: exact match
        assertThat(tick.getPrice().toPlainString()).isEqualTo("45123.456789012345");
    }

    @Test
    void latencyCalculation() {
        Tick tick = new Tick();
        tick.setExchangeTimestamp(Instant.parse("2026-03-23T10:00:00.000Z"));
        tick.setReceivedTimestamp(Instant.parse("2026-03-23T10:00:00.042Z"));

        assertThat(tick.getLatencyMs()).isEqualTo(42);
        assertThat(tick.getLatency()).isEqualTo(Duration.ofMillis(42));
    }

    @Test
    void latencyReturnsZeroWhenTimestampsNull() {
        Tick tick = new Tick();
        // Both null
        assertThat(tick.getLatencyMs()).isEqualTo(0);

        // Only exchange set
        tick.setExchangeTimestamp(Instant.now());
        assertThat(tick.getLatencyMs()).isEqualTo(0);

        // Only received set
        Tick tick2 = new Tick();
        tick2.setReceivedTimestamp(Instant.now());
        assertThat(tick2.getLatencyMs()).isEqualTo(0);
    }

    @Test
    void bidAndAskAreNullable() {
        Tick tick = new Tick("BTCUSDT", new BigDecimal("100"),
                new BigDecimal("1"), 1, Instant.now(), "feed-1");

        assertThat(tick.getBid()).isNull();
        assertThat(tick.getAsk()).isNull();

        tick.setBid(new BigDecimal("99.50"));
        tick.setAsk(new BigDecimal("100.50"));
        assertThat(tick.getBid()).isEqualByComparingTo("99.50");
        assertThat(tick.getAsk()).isEqualByComparingTo("100.50");
    }

    @Test
    void sessionIdIsNullByDefault() {
        Tick tick = new Tick("BTCUSDT", new BigDecimal("100"),
                new BigDecimal("1"), 1, Instant.now(), "feed-1");

        assertThat(tick.getSessionId()).isNull();

        tick.setSessionId(42L);
        assertThat(tick.getSessionId()).isEqualTo(42L);
    }

    @Test
    void defaultConstructorLeavesFieldsUnset() {
        Tick tick = new Tick();

        assertThat(tick.getId()).isEqualTo(0);
        assertThat(tick.getSymbol()).isNull();
        assertThat(tick.getPrice()).isNull();
        assertThat(tick.getVolume()).isNull();
        assertThat(tick.getSequenceNum()).isEqualTo(0);
        assertThat(tick.getExchangeTimestamp()).isNull();
        assertThat(tick.getReceivedTimestamp()).isNull();
        assertThat(tick.getFeedId()).isNull();
        assertThat(tick.getSessionId()).isNull();
    }

    @Test
    void settersAndGettersWorkForAllFields() {
        Tick tick = new Tick();
        Instant now = Instant.now();

        tick.setId(42);
        tick.setSymbol("AAPL");
        tick.setPrice(new BigDecimal("187.42"));
        tick.setBid(new BigDecimal("187.40"));
        tick.setAsk(new BigDecimal("187.44"));
        tick.setVolume(new BigDecimal("100"));
        tick.setSequenceNum(12345);
        tick.setExchangeTimestamp(now);
        tick.setReceivedTimestamp(now.plusMillis(15));
        tick.setFeedId("finnhub-1");
        tick.setSessionId(7L);

        assertThat(tick.getId()).isEqualTo(42);
        assertThat(tick.getSymbol()).isEqualTo("AAPL");
        assertThat(tick.getPrice()).isEqualByComparingTo("187.42");
        assertThat(tick.getBid()).isEqualByComparingTo("187.40");
        assertThat(tick.getAsk()).isEqualByComparingTo("187.44");
        assertThat(tick.getVolume()).isEqualByComparingTo("100");
        assertThat(tick.getSequenceNum()).isEqualTo(12345);
        assertThat(tick.getExchangeTimestamp()).isEqualTo(now);
        assertThat(tick.getReceivedTimestamp()).isEqualTo(now.plusMillis(15));
        assertThat(tick.getFeedId()).isEqualTo("finnhub-1");
        assertThat(tick.getSessionId()).isEqualTo(7L);
    }
}
