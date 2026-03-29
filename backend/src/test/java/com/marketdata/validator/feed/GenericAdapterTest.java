package com.marketdata.validator.feed;

import com.marketdata.validator.model.Tick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GenericAdapterTest {

    private GenericAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new GenericAdapter();
    }

    // --- parseTick with default field mappings ---

    @Test
    void parseValidTradeWithDefaults() {
        String msg = """
                {"type":"trade","symbol":"BTC","price":"45000.50","volume":"1.5","timestamp":1711000000000,"sequence":12345}""";
        Tick tick = adapter.parseTick(msg);

        assertThat(tick).isNotNull();
        assertThat(tick.getSymbol()).isEqualTo("BTC");
        assertThat(tick.getPrice()).isEqualByComparingTo("45000.50");
        assertThat(tick.getVolume()).isEqualByComparingTo("1.5");
        assertThat(tick.getSequenceNum()).isEqualTo(12345L);
        assertThat(tick.getCorrelationId()).isNotNull();
    }

    @Test
    void parseTradeWithoutTypeField() {
        // When there's no 'type' field, treat it as a trade (default behavior)
        String msg = """
                {"symbol":"ETH","price":"3000.00","volume":"2.0","timestamp":1711000000000}""";
        Tick tick = adapter.parseTick(msg);

        assertThat(tick).isNotNull();
        assertThat(tick.getSymbol()).isEqualTo("ETH");
    }

    @Test
    void parseNonTradeTypeReturnsNull() {
        String msg = """
                {"type":"heartbeat","symbol":"BTC"}""";
        assertThat(adapter.parseTick(msg)).isNull();
    }

    @Test
    void parseMissingSymbolReturnsNull() {
        String msg = """
                {"type":"trade","price":"100.00"}""";
        assertThat(adapter.parseTick(msg)).isNull();
    }

    @Test
    void parseMalformedJsonReturnsNull() {
        assertThat(adapter.parseTick("{invalid")).isNull();
    }

    @Test
    void parseMinimalMessage() {
        // Only symbol present — other fields should be handled gracefully
        String msg = """
                {"symbol":"DOGE"}""";
        Tick tick = adapter.parseTick(msg);

        assertThat(tick).isNotNull();
        assertThat(tick.getSymbol()).isEqualTo("DOGE");
        assertThat(tick.getPrice()).isNull();
        assertThat(tick.getExchangeTimestamp()).isNotNull(); // defaults to now
    }

    @Test
    void parseSetsReceivedTimestamp() {
        String msg = """
                {"symbol":"BTC","price":"45000","timestamp":1711000000000}""";
        Tick tick = adapter.parseTick(msg);

        assertThat(tick).isNotNull();
        assertThat(tick.getReceivedTimestamp()).isNotNull();
    }

    // --- Custom field mappings ---

    @Test
    void customFieldMappings() {
        GenericAdapter custom = new GenericAdapter(Map.of(
                "symbol", "sym",
                "price", "px",
                "volume", "qty",
                "timestamp", "ts",
                "sequence", "seq"
        ));

        String msg = """
                {"sym":"AAPL","px":"178.50","qty":"100","ts":1711000000000,"seq":999}""";
        Tick tick = custom.parseTick(msg);

        assertThat(tick).isNotNull();
        assertThat(tick.getSymbol()).isEqualTo("AAPL");
        assertThat(tick.getPrice()).isEqualByComparingTo("178.50");
        assertThat(tick.getVolume()).isEqualByComparingTo("100");
        assertThat(tick.getSequenceNum()).isEqualTo(999L);
    }

    @Test
    void customTypeFieldAndValue() {
        GenericAdapter custom = new GenericAdapter(Map.of(
                "typeField", "event",
                "tradeTypeValue", "execution"
        ));

        String tradeMsg = """
                {"event":"execution","symbol":"BTC","price":"50000"}""";
        assertThat(custom.parseTick(tradeMsg)).isNotNull();

        String nonTradeMsg = """
                {"event":"status","symbol":"BTC"}""";
        assertThat(custom.parseTick(nonTradeMsg)).isNull();
    }

    // --- isHeartbeat ---

    @Test
    void pingIsHeartbeat() {
        assertThat(adapter.isHeartbeat("{\"type\":\"ping\"}")).isTrue();
    }

    @Test
    void heartbeatTypeIsHeartbeat() {
        assertThat(adapter.isHeartbeat("{\"type\":\"heartbeat\"}")).isTrue();
    }

    @Test
    void pongIsHeartbeat() {
        assertThat(adapter.isHeartbeat("{\"type\":\"pong\"}")).isTrue();
    }

    @Test
    void tradeIsNotHeartbeat() {
        assertThat(adapter.isHeartbeat("{\"type\":\"trade\",\"symbol\":\"BTC\"}")).isFalse();
    }

    @Test
    void malformedIsNotHeartbeat() {
        assertThat(adapter.isHeartbeat("{bad json")).isFalse();
    }

    @Test
    void noTypeFieldIsNotHeartbeat() {
        assertThat(adapter.isHeartbeat("{\"symbol\":\"BTC\"}")).isFalse();
    }

    // --- subscribe/unsubscribe ---

    @Test
    void subscribeContainsSymbols() {
        String msg = adapter.getSubscribeMessage(List.of("BTC", "ETH"));
        assertThat(msg).contains("subscribe").contains("BTC").contains("ETH");
    }

    @Test
    void unsubscribeContainsSymbols() {
        String msg = adapter.getUnsubscribeMessage(List.of("BTC"));
        assertThat(msg).contains("unsubscribe").contains("BTC");
    }
}
