package com.marketdata.validator.feed;

import com.marketdata.validator.model.Tick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinnhubAdapterTest {

    private FinnhubAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new FinnhubAdapter();
    }

    // --- parseTick ---

    @Test
    void parseValidTradeMessage() {
        String msg = """
                {"type":"trade","data":[{"s":"AAPL","p":"178.50","v":"100","t":1711000000000}]}""";
        Tick tick = adapter.parseTick(msg);

        assertThat(tick).isNotNull();
        assertThat(tick.getSymbol()).isEqualTo("AAPL");
        assertThat(tick.getPrice()).isEqualByComparingTo("178.50");
        assertThat(tick.getVolume()).isEqualByComparingTo("100");
        assertThat(tick.getExchangeTimestamp()).isNotNull();
        assertThat(tick.getCorrelationId()).isNotNull();
    }

    @Test
    void parseMultipleTradesReturnsList() {
        String msg = """
                {"type":"trade","data":[
                  {"s":"AAPL","p":"178.50","v":"100","t":1711000000000},
                  {"s":"AAPL","p":"178.52","v":"50","t":1711000000001}
                ]}""";
        List<Tick> ticks = adapter.parseTicks(msg);

        assertThat(ticks).hasSize(2);
        assertThat(ticks.get(0).getPrice()).isEqualByComparingTo("178.50");
        assertThat(ticks.get(1).getPrice()).isEqualByComparingTo("178.52");
    }

    @Test
    void parseTickReturnsFirstTradeFromArray() {
        String msg = """
                {"type":"trade","data":[
                  {"s":"MSFT","p":"400.00","v":"200","t":1711000000000},
                  {"s":"MSFT","p":"401.00","v":"50","t":1711000000001}
                ]}""";
        Tick tick = adapter.parseTick(msg);

        assertThat(tick).isNotNull();
        assertThat(tick.getPrice()).isEqualByComparingTo("400.00");
    }

    @Test
    void parseNonTradeMessageReturnsNull() {
        String msg = """
                {"type":"ping"}""";
        assertThat(adapter.parseTick(msg)).isNull();
    }

    @Test
    void parseEmptyDataArrayReturnsNull() {
        String msg = """
                {"type":"trade","data":[]}""";
        assertThat(adapter.parseTick(msg)).isNull();
    }

    @Test
    void parseMissingDataFieldReturnsNull() {
        String msg = """
                {"type":"trade"}""";
        assertThat(adapter.parseTick(msg)).isNull();
    }

    @Test
    void parseMalformedJsonReturnsNull() {
        assertThat(adapter.parseTick("{invalid json")).isNull();
    }

    @Test
    void parseTicksFromNonTradeReturnsEmptyList() {
        String msg = """
                {"type":"ping"}""";
        assertThat(adapter.parseTicks(msg)).isEmpty();
    }

    @Test
    void parseSubscribeConfirmationReturnsNull() {
        String msg = """
                {"type":"subscribe-confirm","symbol":"AAPL"}""";
        assertThat(adapter.parseTick(msg)).isNull();
    }

    // --- isHeartbeat ---

    @Test
    void pingIsHeartbeat() {
        assertThat(adapter.isHeartbeat("{\"type\":\"ping\"}")).isTrue();
    }

    @Test
    void tradeIsNotHeartbeat() {
        String msg = """
                {"type":"trade","data":[{"s":"AAPL","p":"178.50","v":"100","t":1711000000000}]}""";
        assertThat(adapter.isHeartbeat(msg)).isFalse();
    }

    @Test
    void malformedJsonIsNotHeartbeat() {
        assertThat(adapter.isHeartbeat("{bad json")).isFalse();
    }

    // --- subscribe/unsubscribe messages ---

    @Test
    void subscribeMessageContainsSymbol() {
        String msg = adapter.getSubscribeMessage(List.of("AAPL"));
        assertThat(msg).contains("subscribe").contains("AAPL");
    }

    @Test
    void subscribeMultipleSymbols() {
        String msg = adapter.getSubscribeMessage(List.of("AAPL", "MSFT", "TSLA"));
        assertThat(msg).contains("AAPL").contains("MSFT").contains("TSLA");
    }

    @Test
    void unsubscribeMessageContainsSymbol() {
        String msg = adapter.getUnsubscribeMessage(List.of("AAPL"));
        assertThat(msg).contains("unsubscribe").contains("AAPL");
    }

    // --- Sequence number from timestamp ---

    @Test
    void sequenceNumUsesTimestamp() {
        String msg = """
                {"type":"trade","data":[{"s":"GOOG","p":"155.00","v":"30","t":1711000099999}]}""";
        Tick tick = adapter.parseTick(msg);

        assertThat(tick).isNotNull();
        assertThat(tick.getSequenceNum()).isEqualTo(1711000099999L);
    }

    // --- Received timestamp is set ---

    @Test
    void receivedTimestampIsSet() {
        String msg = """
                {"type":"trade","data":[{"s":"AAPL","p":"178.50","v":"100","t":1711000000000}]}""";
        Tick tick = adapter.parseTick(msg);

        assertThat(tick).isNotNull();
        assertThat(tick.getReceivedTimestamp()).isNotNull();
    }

    // --- parseTradeNode missing fields ---

    @Test
    void parseTradeNodeMissingSymbolReturnsNull() {
        String msg = """
                {"type":"trade","data":[{"p":"178.50","v":"100","t":1711000000000}]}""";
        assertThat(adapter.parseTick(msg)).isNull();
    }

    @Test
    void parseTradeNodeMissingPriceReturnsNull() {
        String msg = """
                {"type":"trade","data":[{"s":"AAPL","v":"100","t":1711000000000}]}""";
        assertThat(adapter.parseTick(msg)).isNull();
    }

    @Test
    void parseTradeNodeMissingVolumeReturnsNull() {
        String msg = """
                {"type":"trade","data":[{"s":"AAPL","p":"178.50","t":1711000000000}]}""";
        assertThat(adapter.parseTick(msg)).isNull();
    }

    @Test
    void parseTradeNodeMissingTimestampReturnsNull() {
        String msg = """
                {"type":"trade","data":[{"s":"AAPL","p":"178.50","v":"100"}]}""";
        assertThat(adapter.parseTick(msg)).isNull();
    }

    @Test
    void parseTicksWithMixedValidAndInvalidNodes() {
        String msg = """
                {"type":"trade","data":[
                  {"s":"AAPL","p":"178.50","v":"100","t":1711000000000},
                  {"p":"178.50","v":"100","t":1711000000001}
                ]}""";
        List<Tick> ticks = adapter.parseTicks(msg);
        assertThat(ticks).hasSize(1);
        assertThat(ticks.get(0).getSymbol()).isEqualTo("AAPL");
    }
}
