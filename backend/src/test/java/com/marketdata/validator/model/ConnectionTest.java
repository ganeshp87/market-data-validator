package com.marketdata.validator.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionTest {

    @Test
    void defaultConstructorGeneratesUUID() {
        Connection c1 = new Connection();
        Connection c2 = new Connection();

        assertThat(c1.getId()).isNotNull();
        assertThat(c1.getId()).hasSize(36); // UUID format: 8-4-4-4-12
        assertThat(c1.getId()).isNotEqualTo(c2.getId());
    }

    @Test
    void defaultConstructorSetsDisconnectedStatus() {
        Connection conn = new Connection();
        assertThat(conn.getStatus()).isEqualTo(Connection.Status.DISCONNECTED);
    }

    @Test
    void defaultConstructorInitializesEmptySymbolList() {
        Connection conn = new Connection();
        assertThat(conn.getSymbols()).isNotNull();
        assertThat(conn.getSymbols()).isEmpty();
    }

    @Test
    void parameterizedConstructorSetsAllFields() {
        Connection conn = new Connection("Binance BTC",
                "wss://stream.binance.com:9443/ws/btcusdt@trade",
                Connection.AdapterType.BINANCE,
                List.of("BTCUSDT", "ETHUSDT"));

        assertThat(conn.getName()).isEqualTo("Binance BTC");
        assertThat(conn.getUrl()).isEqualTo("wss://stream.binance.com:9443/ws/btcusdt@trade");
        assertThat(conn.getAdapterType()).isEqualTo(Connection.AdapterType.BINANCE);
        assertThat(conn.getSymbols()).containsExactly("BTCUSDT", "ETHUSDT");
        assertThat(conn.getStatus()).isEqualTo(Connection.Status.DISCONNECTED);
        assertThat(conn.getId()).isNotNull();
    }

    @Test
    void symbolListIsDefensivelyCopied() {
        List<String> original = new java.util.ArrayList<>(List.of("BTCUSDT"));
        Connection conn = new Connection("Test", "wss://test", Connection.AdapterType.GENERIC, original);

        // Modify original list — should NOT affect connection's list
        original.add("ETHUSDT");
        assertThat(conn.getSymbols()).containsExactly("BTCUSDT");
    }

    @Test
    void setSymbolsDefensivelyCopies() {
        Connection conn = new Connection();
        List<String> symbols = new java.util.ArrayList<>(List.of("AAPL"));
        conn.setSymbols(symbols);

        symbols.add("MSFT");
        assertThat(conn.getSymbols()).containsExactly("AAPL");
    }

    @Test
    void recordTickUpdatesTimestampAndCount() {
        Connection conn = new Connection();
        assertThat(conn.getTickCount()).isEqualTo(0);
        assertThat(conn.getLastTickAt()).isNull();

        Instant before = Instant.now();
        conn.recordTick();
        Instant after = Instant.now();

        assertThat(conn.getTickCount()).isEqualTo(1);
        assertThat(conn.getLastTickAt()).isBetween(before, after);

        conn.recordTick();
        conn.recordTick();
        assertThat(conn.getTickCount()).isEqualTo(3);
    }

    @Test
    void allStatusValuesExist() {
        // Verify all 4 statuses from the blueprint exist
        assertThat(Connection.Status.values()).containsExactly(
                Connection.Status.CONNECTED,
                Connection.Status.DISCONNECTED,
                Connection.Status.RECONNECTING,
                Connection.Status.ERROR
        );
    }

    @Test
    void allAdapterTypesExist() {
        // Verify all 3 adapter types from the blueprint exist
        assertThat(Connection.AdapterType.values()).containsExactly(
                Connection.AdapterType.BINANCE,
                Connection.AdapterType.FINNHUB,
                Connection.AdapterType.GENERIC
        );
    }

    @Test
    void settersAndGettersWorkForAllFields() {
        Connection conn = new Connection();
        Instant now = Instant.now();

        conn.setId("custom-id");
        conn.setName("Finnhub Stocks");
        conn.setUrl("wss://ws.finnhub.io");
        conn.setAdapterType(Connection.AdapterType.FINNHUB);
        conn.setSymbols(List.of("AAPL", "MSFT"));
        conn.setStatus(Connection.Status.CONNECTED);
        conn.setConnectedAt(now);
        conn.setLastTickAt(now);
        conn.setTickCount(5000);

        assertThat(conn.getId()).isEqualTo("custom-id");
        assertThat(conn.getName()).isEqualTo("Finnhub Stocks");
        assertThat(conn.getUrl()).isEqualTo("wss://ws.finnhub.io");
        assertThat(conn.getAdapterType()).isEqualTo(Connection.AdapterType.FINNHUB);
        assertThat(conn.getSymbols()).containsExactly("AAPL", "MSFT");
        assertThat(conn.getStatus()).isEqualTo(Connection.Status.CONNECTED);
        assertThat(conn.getConnectedAt()).isEqualTo(now);
        assertThat(conn.getLastTickAt()).isEqualTo(now);
        assertThat(conn.getTickCount()).isEqualTo(5000);
    }
}
