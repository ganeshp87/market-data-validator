package com.marketdata.validator.store;

import com.marketdata.validator.model.Tick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class TickStoreTest {

    @Autowired
    private TickStore tickStore;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM ticks");
    }

    // --- Save and retrieve ---

    @Test
    void saveAndFindBySessionId() {
        Tick tick = createTick("BTCUSDT", "45123.45", 1, 100L);
        tickStore.save(tick);

        List<Tick> found = tickStore.findBySessionId(100L);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getSymbol()).isEqualTo("BTCUSDT");
        assertThat(found.get(0).getPrice()).isEqualByComparingTo(new BigDecimal("45123.45"));
    }

    @Test
    void pricesPrecisionPreserved() {
        Tick tick = createTick("ETHUSDT", "3456.789012345", 1, 100L);
        tickStore.save(tick);

        List<Tick> found = tickStore.findBySessionId(100L);
        assertThat(found.get(0).getPrice().toPlainString()).isEqualTo("3456.789012345");
    }

    @Test
    void bidAskVolumeNullable() {
        Tick tick = createTick("BTCUSDT", "45000", 1, 100L);
        // bid, ask, volume are null by default (not set)
        tick.setBid(null);
        tick.setAsk(null);
        tick.setVolume(null);
        tickStore.save(tick);

        List<Tick> found = tickStore.findBySessionId(100L);
        assertThat(found.get(0).getBid()).isNull();
        assertThat(found.get(0).getAsk()).isNull();
        assertThat(found.get(0).getVolume()).isNull();
    }

    @Test
    void bidAskPreserved() {
        Tick tick = createTick("BTCUSDT", "45000", 1, 100L);
        tick.setBid(new BigDecimal("44999.50"));
        tick.setAsk(new BigDecimal("45000.50"));
        tickStore.save(tick);

        List<Tick> found = tickStore.findBySessionId(100L);
        assertThat(found.get(0).getBid()).isEqualByComparingTo(new BigDecimal("44999.50"));
        assertThat(found.get(0).getAsk()).isEqualByComparingTo(new BigDecimal("45000.50"));
    }

    // --- Batch insert ---

    @Test
    void saveBatchInsertsAll() {
        List<Tick> ticks = List.of(
                createTick("BTCUSDT", "45000", 1, 100L),
                createTick("BTCUSDT", "45001", 2, 100L),
                createTick("ETHUSDT", "3400", 3, 100L)
        );
        tickStore.saveBatch(ticks);

        assertThat(tickStore.countBySessionId(100L)).isEqualTo(3);
    }

    @Test
    void saveBatchEmptyListDoesNothing() {
        tickStore.saveBatch(List.of());
        assertThat(tickStore.countBySessionId(100L)).isEqualTo(0);
    }

    // --- Find by symbol ---

    @Test
    void findBySymbolWithTimeRange() {
        Instant base = Instant.parse("2026-03-23T10:00:00Z");
        tickStore.save(createTickAt("BTCUSDT", "45000", 1, base));
        tickStore.save(createTickAt("BTCUSDT", "45100", 2, base.plusSeconds(60)));
        tickStore.save(createTickAt("BTCUSDT", "45200", 3, base.plusSeconds(120)));
        tickStore.save(createTickAt("ETHUSDT", "3400", 4, base.plusSeconds(30)));

        List<Tick> found = tickStore.findBySymbol("BTCUSDT",
                base, base.plusSeconds(90));
        assertThat(found).hasSize(2); // First two BTC ticks, not the third or ETH
    }

    @Test
    void findBySymbolReturnsOrderedByTimestamp() {
        Instant base = Instant.parse("2026-03-23T10:00:00Z");
        tickStore.save(createTickAt("BTCUSDT", "45200", 3, base.plusSeconds(2)));
        tickStore.save(createTickAt("BTCUSDT", "45000", 1, base));
        tickStore.save(createTickAt("BTCUSDT", "45100", 2, base.plusSeconds(1)));

        List<Tick> found = tickStore.findBySymbol("BTCUSDT",
                base, base.plusSeconds(3));
        assertThat(found).hasSize(3);
        assertThat(found.get(0).getPrice()).isEqualByComparingTo(new BigDecimal("45000"));
        assertThat(found.get(2).getPrice()).isEqualByComparingTo(new BigDecimal("45200"));
    }

    // --- Count ---

    @Test
    void countBySessionId() {
        tickStore.save(createTick("BTCUSDT", "45000", 1, 100L));
        tickStore.save(createTick("BTCUSDT", "45001", 2, 100L));
        tickStore.save(createTick("BTCUSDT", "45002", 3, 200L));

        assertThat(tickStore.countBySessionId(100L)).isEqualTo(2);
        assertThat(tickStore.countBySessionId(200L)).isEqualTo(1);
        assertThat(tickStore.countBySessionId(999L)).isEqualTo(0);
    }

    // --- Delete ---

    @Test
    void deleteBySessionId() {
        tickStore.save(createTick("BTCUSDT", "45000", 1, 100L));
        tickStore.save(createTick("BTCUSDT", "45001", 2, 100L));
        tickStore.save(createTick("BTCUSDT", "45002", 3, 200L));

        tickStore.deleteBySessionId(100L);

        assertThat(tickStore.countBySessionId(100L)).isEqualTo(0);
        assertThat(tickStore.countBySessionId(200L)).isEqualTo(1); // Not affected
    }

    // --- Session ID nullable ---

    @Test
    void nullSessionIdForNonRecordedTicks() {
        Tick tick = createTick("BTCUSDT", "45000", 1, null);
        tickStore.save(tick);

        // Can't find by session_id = null with findBySessionId,
        // but verify it doesn't throw
        assertThat(tickStore.countBySessionId(0)).isEqualTo(0);
    }

    // --- Timestamps preserved ---

    @Test
    void timestampsRoundTrip() {
        Instant exchangeTs = Instant.parse("2026-03-23T10:00:00.123Z");
        Instant receivedTs = Instant.parse("2026-03-23T10:00:00.165Z");

        Tick tick = createTick("BTCUSDT", "45000", 1, 100L);
        tick.setExchangeTimestamp(exchangeTs);
        tick.setReceivedTimestamp(receivedTs);
        tickStore.save(tick);

        List<Tick> found = tickStore.findBySessionId(100L);
        assertThat(found.get(0).getExchangeTimestamp()).isEqualTo(exchangeTs);
        assertThat(found.get(0).getReceivedTimestamp()).isEqualTo(receivedTs);
    }

    // --- Helpers ---

    private Tick createTick(String symbol, String price, long seqNum, Long sessionId) {
        Tick tick = new Tick(symbol, new BigDecimal(price), new BigDecimal("1"),
                seqNum, Instant.parse("2026-03-23T10:00:00Z"), "test-feed");
        tick.setSessionId(sessionId);
        return tick;
    }

    private Tick createTickAt(String symbol, String price, long seqNum, Instant exchangeTs) {
        Tick tick = new Tick(symbol, new BigDecimal(price), new BigDecimal("1"),
                seqNum, exchangeTs, "test-feed");
        tick.setSessionId(100L);
        return tick;
    }
}
