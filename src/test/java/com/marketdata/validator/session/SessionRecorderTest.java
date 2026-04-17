package com.marketdata.validator.session;

import com.marketdata.validator.model.Session;
import com.marketdata.validator.model.Tick;
import com.marketdata.validator.store.SessionStore;
import com.marketdata.validator.store.TickStore;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class SessionRecorderTest {

    @Autowired
    private SessionRecorder recorder;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private TickStore tickStore;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // Stop any ongoing recording from previous test
        if (recorder.isRecording()) {
            recorder.stop();
        }
        jdbc.update("DELETE FROM ticks");
        jdbc.update("DELETE FROM sessions");
    }

    // --- Start recording ---

    @Test
    void startCreatesSessionInDb() {
        Session session = recorder.start("Test Session", "feed-1");

        assertThat(session.getId()).isGreaterThan(0);
        assertThat(session.getName()).isEqualTo("Test Session");
        assertThat(session.getFeedId()).isEqualTo("feed-1");
        assertThat(session.getStatus()).isEqualTo(Session.Status.RECORDING);
        assertThat(session.getStartedAt()).isNotNull();
    }

    @Test
    void startSetsRecordingFlag() {
        recorder.start("Test", "feed-1");

        assertThat(recorder.isRecording()).isTrue();
        assertThat(recorder.getCurrentSession()).isNotNull();
    }

    @Test
    void startThrowsWhenAlreadyRecording() {
        recorder.start("First", "feed-1");

        assertThatThrownBy(() -> recorder.start("Second", "feed-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Already recording");
    }

    // --- onTick ---

    @Test
    void ticksSavedToDatabaseWhenBatchFull() {
        Session session = recorder.start("Batch Test", "feed-1");

        // Send exactly BATCH_SIZE ticks to trigger flush
        for (int i = 0; i < SessionRecorder.BATCH_SIZE; i++) {
            recorder.onTick(createTick("BTCUSDT", "45000", i + 1));
        }

        // Should have been flushed to DB
        assertThat(tickStore.countBySessionId(session.getId()))
                .isEqualTo(SessionRecorder.BATCH_SIZE);
    }

    @Test
    void ticksBufferedBelowBatchSize() {
        Session session = recorder.start("Buffer Test", "feed-1");

        // Send fewer than BATCH_SIZE ticks
        for (int i = 0; i < 10; i++) {
            recorder.onTick(createTick("BTCUSDT", "45000", i + 1));
        }

        // Ticks are in memory buffer, not yet in DB
        assertThat(recorder.getBufferSize()).isEqualTo(10);
        assertThat(tickStore.countBySessionId(session.getId())).isEqualTo(0);
    }

    @Test
    void ticksIgnoredWhenNotRecording() {
        // Not recording — ticks should be silently dropped
        long countBefore = recorder.getTickCount();
        recorder.onTick(createTick("BTCUSDT", "45000", 1));

        assertThat(recorder.getTickCount()).isEqualTo(countBefore);
        assertThat(recorder.getBufferSize()).isEqualTo(0);
    }

    @Test
    void tickSessionIdSetAutomatically() {
        Session session = recorder.start("SessionId Test", "feed-1");

        Tick tick = createTick("BTCUSDT", "45000", 1);
        recorder.onTick(tick);

        assertThat(tick.getSessionId()).isEqualTo(session.getId());
    }

    @Test
    void tickCountTracksAllTicks() {
        recorder.start("Count Test", "feed-1");

        for (int i = 0; i < 250; i++) {
            recorder.onTick(createTick("BTCUSDT", "45000", i + 1));
        }

        assertThat(recorder.getTickCount()).isEqualTo(250);
    }

    // --- flushNow ---

    @Test
    void flushNowWritesBufferedTicks() {
        Session session = recorder.start("Flush Test", "feed-1");

        for (int i = 0; i < 10; i++) {
            recorder.onTick(createTick("BTCUSDT", "45000", i + 1));
        }
        assertThat(tickStore.countBySessionId(session.getId())).isEqualTo(0);

        recorder.flushNow();

        assertThat(tickStore.countBySessionId(session.getId())).isEqualTo(10);
        assertThat(recorder.getBufferSize()).isEqualTo(0);
    }

    // --- Stop recording ---

    @Test
    void stopFlushesRemainingTicksAndFinalizesSession() {
        Session session = recorder.start("Stop Test", "feed-1");

        for (int i = 0; i < 50; i++) {
            recorder.onTick(createTick("BTCUSDT", "45000", i + 1));
        }

        Session completed = recorder.stop();

        assertThat(completed.getStatus()).isEqualTo(Session.Status.COMPLETED);
        assertThat(completed.getEndedAt()).isNotNull();
        assertThat(completed.getTickCount()).isEqualTo(50);
        assertThat(completed.getByteSize()).isGreaterThan(0);
    }

    @Test
    void stopWritesAllTicksToDatabase() {
        Session session = recorder.start("DB Test", "feed-1");

        for (int i = 0; i < 50; i++) {
            recorder.onTick(createTick("BTCUSDT", "45000", i + 1));
        }

        recorder.stop();

        assertThat(tickStore.countBySessionId(session.getId())).isEqualTo(50);
    }

    @Test
    void stopClearsRecordingState() {
        recorder.start("Clear Test", "feed-1");
        recorder.stop();

        assertThat(recorder.isRecording()).isFalse();
        assertThat(recorder.getCurrentSession()).isNull();
    }

    @Test
    void stopThrowsWhenNotRecording() {
        assertThatThrownBy(recorder::stop)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Not currently recording");
    }

    @Test
    void stopUpdatesSessionInDatabase() {
        Session session = recorder.start("DB Update Test", "feed-1");

        for (int i = 0; i < 5; i++) {
            recorder.onTick(createTick("BTCUSDT", "45000", i + 1));
        }

        recorder.stop();

        Session fromDb = sessionStore.findById(session.getId()).orElseThrow();
        assertThat(fromDb.getStatus()).isEqualTo(Session.Status.COMPLETED);
        assertThat(fromDb.getTickCount()).isEqualTo(5);
        assertThat(fromDb.getByteSize()).isGreaterThan(0);
    }

    // --- Multiple sessions ---

    @Test
    void canStartNewSessionAfterStopping() {
        Session first = recorder.start("First", "feed-1");
        recorder.onTick(createTick("BTCUSDT", "45000", 1));
        recorder.stop();

        Session second = recorder.start("Second", "feed-2");
        recorder.onTick(createTick("ETHUSDT", "3400", 1));
        recorder.stop();

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(tickStore.countBySessionId(first.getId())).isEqualTo(1);
        assertThat(tickStore.countBySessionId(second.getId())).isEqualTo(1);
    }

    @Test
    void tickPricePreservedThroughRecording() {
        Session session = recorder.start("Precision Test", "feed-1");
        recorder.onTick(createTick("BTCUSDT", "45123.456789012", 1));
        recorder.stop();

        List<Tick> ticks = tickStore.findBySessionId(session.getId());
        assertThat(ticks.get(0).getPrice().toPlainString())
                .isEqualTo("45123.456789012");
    }

    @Test
    void flushClearsBufferEvenOnSuccess() {
        Session session = recorder.start("Flush Buffer Test", "feed-1");

        // Submit ticks just below batch threshold, then force flush
        for (int i = 1; i <= 10; i++) {
            recorder.onTick(createTick("ETH", "3000", i));
        }
        assertThat(recorder.getBufferSize()).isEqualTo(10);
        recorder.flushNow();
        assertThat(recorder.getBufferSize()).isEqualTo(0);

        // Recording should continue normally after flush
        recorder.onTick(createTick("ETH", "3001", 11));
        assertThat(recorder.getBufferSize()).isEqualTo(1);
        recorder.stop();

        assertThat(tickStore.countBySessionId(session.getId())).isEqualTo(11);
    }

    @Test
    void destroyDoesNotThrowWhenNotRecording() {
        // When not recording, destroy() should be a no-op without error
        recorder.destroy();
        assertThat(recorder.isRecording()).isFalse();
    }

    // --- Helpers ---

    private Tick createTick(String symbol, String price, long seqNum) {
        return new Tick(symbol, new BigDecimal(price), new BigDecimal("1"),
                seqNum, Instant.now(), "test-feed");
    }
}
