package com.marketdata.validator.session;

import com.marketdata.validator.model.Session;
import com.marketdata.validator.model.Tick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionExporterTest {

    private SessionExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new SessionExporter();
    }

    // --- JSON export ---

    @Test
    void exportAsJsonContainsSessionMetadata() {
        Session session = createSession(1L, "test-session");
        List<Tick> ticks = List.of(createTick("BTC", "45000.00"));

        Map<String, Object> json = exporter.exportAsJson(session, ticks);

        assertThat(json.get("sessionId")).isEqualTo(1L);
        assertThat(json.get("name")).isEqualTo("test-session");
        assertThat(json.get("feedId")).isEqualTo("feed-1");
    }

    @Test
    void exportAsJsonContainsTicks() {
        Session session = createSession(1L, "test-session");
        List<Tick> ticks = List.of(
                createTick("BTC", "45000.00"),
                createTick("ETH", "3000.00")
        );

        Map<String, Object> json = exporter.exportAsJson(session, ticks);

        @SuppressWarnings("unchecked")
        List<Tick> exportedTicks = (List<Tick>) json.get("ticks");
        assertThat(exportedTicks).hasSize(2);
    }

    @Test
    void exportAsJsonEmptyTicksList() {
        Session session = createSession(1L, "empty-session");
        Map<String, Object> json = exporter.exportAsJson(session, List.of());

        @SuppressWarnings("unchecked")
        List<Tick> exportedTicks = (List<Tick>) json.get("ticks");
        assertThat(exportedTicks).isEmpty();
    }

    // --- CSV export ---

    @Test
    void exportAsCsvHasHeaders() {
        String csv = exporter.exportAsCsv(List.of());
        assertThat(csv).startsWith("symbol,price,bid,ask,volume,sequenceNum,exchangeTimestamp,receivedTimestamp,feedId,correlationId");
    }

    @Test
    void exportAsCsvHasDataRows() {
        List<Tick> ticks = List.of(
                createTick("BTC", "45000.00"),
                createTick("ETH", "3000.00")
        );

        String csv = exporter.exportAsCsv(ticks);
        String[] lines = csv.split("\n");

        assertThat(lines).hasSize(3); // header + 2 data rows
        assertThat(lines[1]).startsWith("BTC,45000.00");
        assertThat(lines[2]).startsWith("ETH,3000.00");
    }

    @Test
    void exportAsCsvPreservesPrecision() {
        Tick tick = createTick("BTC", "45123.456789");
        String csv = exporter.exportAsCsv(List.of(tick));

        assertThat(csv).contains("45123.456789");
    }

    @Test
    void exportAsCsvHandlesNullBidAsk() {
        Tick tick = createTick("BTC", "45000.00");
        // bid and ask are null by default
        String csv = exporter.exportAsCsv(List.of(tick));
        String dataLine = csv.split("\n")[1];

        // bid and ask columns should be empty (two consecutive commas after price)
        assertThat(dataLine).contains("45000.00,,,");
    }

    // --- Helpers ---

    private Session createSession(long id, String name) {
        Session session = new Session();
        session.setId(id);
        session.setName(name);
        session.setFeedId("feed-1");
        session.setStartedAt(Instant.now());
        return session;
    }

    private Tick createTick(String symbol, String price) {
        Tick tick = new Tick(symbol, new BigDecimal(price), BigDecimal.ONE,
                1L, Instant.now(), "feed-1");
        return tick;
    }
}
