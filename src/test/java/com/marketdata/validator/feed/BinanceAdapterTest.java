package com.marketdata.validator.feed;

import com.marketdata.validator.model.Tick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceAdapterTest {

    private BinanceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BinanceAdapter();
    }

    // --- parseTick tests ---

    @Test
    void parseValidTradeMessage() {
        String msg = """
                {
                  "e": "trade",
                  "s": "BTCUSDT",
                  "p": "45123.45",
                  "q": "0.123",
                  "T": 1711000000000,
                  "t": 123456789
                }
                """;

        Tick tick = adapter.parseTick(msg);

        assertThat(tick).isNotNull();
        assertThat(tick.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(tick.getPrice()).isEqualByComparingTo(new BigDecimal("45123.45"));
        assertThat(tick.getVolume()).isEqualByComparingTo(new BigDecimal("0.123"));
        assertThat(tick.getExchangeTimestamp()).isEqualTo(Instant.ofEpochMilli(1711000000000L));
        assertThat(tick.getSequenceNum()).isEqualTo(123456789);
        assertThat(tick.getReceivedTimestamp()).isNotNull();
    }

    @Test
    void parsePriceAsBigDecimalPreservesPrecision() {
        String msg = """
                {
                  "e": "trade",
                  "s": "BTCUSDT",
                  "p": "45123.456789012345",
                  "q": "0.000001",
                  "T": 1711000000000,
                  "t": 1
                }
                """;

        Tick tick = adapter.parseTick(msg);

        // BigDecimal preserves all digits — double would truncate
        assertThat(tick.getPrice().toPlainString()).isEqualTo("45123.456789012345");
        assertThat(tick.getVolume().toPlainString()).isEqualTo("0.000001");
    }

    @Test
    void parseNonTradeMessageReturnsNull() {
        // Subscription confirmation — not a trade
        String msg = """
                {
                  "result": null,
                  "id": 1
                }
                """;

        Tick tick = adapter.parseTick(msg);
        assertThat(tick).isNull();
    }

    @Test
    void parseMalformedJsonReturnsNull() {
        Tick tick = adapter.parseTick("not json at all {{{");
        assertThat(tick).isNull();
    }

    @Test
    void parseEmptyStringReturnsNull() {
        Tick tick = adapter.parseTick("");
        assertThat(tick).isNull();
    }

    @Test
    void parseMessageWithWrongEventTypeReturnsNull() {
        String msg = """
                {
                  "e": "aggTrade",
                  "s": "BTCUSDT",
                  "p": "45000",
                  "q": "1",
                  "T": 1711000000000,
                  "t": 1
                }
                """;

        Tick tick = adapter.parseTick(msg);
        assertThat(tick).isNull();
    }

    @Test
    void parseMessageMissingFieldsReturnsNull() {
        // Missing "p" (price) field
        String msg = """
                {
                  "e": "trade",
                  "s": "BTCUSDT",
                  "q": "0.5",
                  "T": 1711000000000,
                  "t": 1
                }
                """;

        Tick tick = adapter.parseTick(msg);
        assertThat(tick).isNull();
    }

    // --- isHeartbeat tests ---

    @Test
    void subscriptionConfirmationIsHeartbeat() {
        String msg = """
                {"result": null, "id": 1}
                """;

        assertThat(adapter.isHeartbeat(msg)).isTrue();
    }

    @Test
    void tradeMessageIsNotHeartbeat() {
        String msg = """
                {
                  "e": "trade",
                  "s": "BTCUSDT",
                  "p": "45000",
                  "q": "1",
                  "T": 1711000000000,
                  "t": 1
                }
                """;

        assertThat(adapter.isHeartbeat(msg)).isFalse();
    }

    @Test
    void malformedJsonIsNotHeartbeat() {
        assertThat(adapter.isHeartbeat("not json")).isFalse();
    }

    // --- Subscribe/Unsubscribe message tests ---

    @Test
    void subscribeMessageFormatsCorrectly() {
        String msg = adapter.getSubscribeMessage(List.of("BTCUSDT", "ETHUSDT"));

        assertThat(msg).contains("\"method\":\"SUBSCRIBE\"");
        assertThat(msg).contains("btcusdt@trade");
        assertThat(msg).contains("ethusdt@trade");
        assertThat(msg).contains("\"id\":1");
    }

    @Test
    void unsubscribeMessageFormatsCorrectly() {
        String msg = adapter.getUnsubscribeMessage(List.of("BTCUSDT"));

        assertThat(msg).contains("\"method\":\"UNSUBSCRIBE\"");
        assertThat(msg).contains("btcusdt@trade");
        assertThat(msg).contains("\"id\":2");
    }

    @Test
    void subscribeConvertsSymbolsToLowerCase() {
        // Binance requires lowercase symbols in stream names
        String msg = adapter.getSubscribeMessage(List.of("BTCUSDT"));
        assertThat(msg).contains("btcusdt@trade");
        assertThat(msg).doesNotContain("BTCUSDT@trade");
    }

    @Test
    void subscribeHandlesSingleSymbol() {
        String msg = adapter.getSubscribeMessage(List.of("ETHUSDT"));
        assertThat(msg).contains("ethusdt@trade");
    }

    // --- Edge cases: malformed and extreme inputs ---

    @Test
    void parseNullInputReturnsNull() {
        Tick tick = adapter.parseTick(null);
        assertThat(tick).isNull();
    }

    @Test
    void parseExtremeLargePriceHandled() {
        String msg = """
                {
                  "e": "trade",
                  "s": "BTCUSDT",
                  "p": "99999999999999.99999999",
                  "q": "0.00000001",
                  "T": 1711000000000,
                  "t": 1
                }
                """;

        Tick tick = adapter.parseTick(msg);
        assertThat(tick).isNotNull();
        assertThat(tick.getPrice().toPlainString()).isEqualTo("99999999999999.99999999");
    }

    @Test
    void parseZeroPriceReturnsValidTick() {
        String msg = """
                {
                  "e": "trade",
                  "s": "BTCUSDT",
                  "p": "0",
                  "q": "1",
                  "T": 1711000000000,
                  "t": 1
                }
                """;

        Tick tick = adapter.parseTick(msg);
        assertThat(tick).isNotNull();
        assertThat(tick.getPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void parseMessageWithExtraFieldsStillWorks() {
        String msg = """
                {
                  "e": "trade",
                  "s": "BTCUSDT",
                  "p": "45000",
                  "q": "1",
                  "T": 1711000000000,
                  "t": 1,
                  "m": true,
                  "M": true,
                  "b": 123456,
                  "a": 789012
                }
                """;

        Tick tick = adapter.parseTick(msg);
        assertThat(tick).isNotNull();
        assertThat(tick.getSymbol()).isEqualTo("BTCUSDT");
    }

    @Test
    void parseMessageMissingVolumeReturnsNull() {
        String msg = """
                {
                  "e": "trade",
                  "s": "BTCUSDT",
                  "p": "45000",
                  "T": 1711000000000,
                  "t": 1
                }
                """;

        Tick tick = adapter.parseTick(msg);
        assertThat(tick).isNull();
    }

    @Test
    void parseMessageMissingTimestampReturnsNull() {
        String msg = """
                {
                  "e": "trade",
                  "s": "BTCUSDT",
                  "p": "45000",
                  "q": "1",
                  "t": 1
                }
                """;

        Tick tick = adapter.parseTick(msg);
        assertThat(tick).isNull();
    }

    @Test
    void parseMessageMissingSymbolReturnsNull() {
        String msg = """
                {
                  "e": "trade",
                  "p": "45000",
                  "q": "1",
                  "T": 1711000000000,
                  "t": 1
                }
                """;

        Tick tick = adapter.parseTick(msg);
        assertThat(tick).isNull();
    }

    @Test
    void isHeartbeatNullReturnsFalse() {
        assertThat(adapter.isHeartbeat(null)).isFalse();
    }

    @Test
    void subscribeEmptyListFormatsCorrectly() {
        String msg = adapter.getSubscribeMessage(List.of());
        assertThat(msg).contains("\"method\":\"SUBSCRIBE\"");
        assertThat(msg).contains("\"params\":[]");
    }
}
