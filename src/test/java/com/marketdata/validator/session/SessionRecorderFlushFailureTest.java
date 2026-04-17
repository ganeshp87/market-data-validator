package com.marketdata.validator.session;

import com.marketdata.validator.feed.FeedManager;
import com.marketdata.validator.model.Session;
import com.marketdata.validator.model.Tick;
import com.marketdata.validator.store.SessionStore;
import com.marketdata.validator.store.TickStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SessionRecorder flush failure behavior.
 * Uses mock TickStore that throws on saveBatch() to verify
 * the error containment fix without a real database.
 */
class SessionRecorderFlushFailureTest {

    private SessionStore mockSessionStore;
    private TickStore mockTickStore;
    private SessionRecorder recorder;

    @BeforeEach
    void setUp() {
        mockSessionStore = mock(SessionStore.class);
        mockTickStore = mock(TickStore.class);
        FeedManager mockFeedManager = mock(FeedManager.class);

        // SessionStore.create() returns a session with a generated ID
        when(mockSessionStore.create(any(Session.class))).thenAnswer(invocation -> {
            Session s = invocation.getArgument(0);
            s.setId(1L);
            return s;
        });

        recorder = new SessionRecorder(mockSessionStore, mockTickStore, mockFeedManager);
    }

    @Test
    void flushFailureDoesNotCrashIngestionPipeline() {
        doThrow(new RuntimeException("DB write failed"))
                .when(mockTickStore).saveBatch(any());

        recorder.start("Failure Test", "feed-1");

        // Submit enough ticks to trigger a flush (BATCH_SIZE = 100)
        for (int i = 1; i <= 100; i++) {
            recorder.onTick(createTick(i));
        }

        // Pipeline should still be alive — not crashed
        assertThat(recorder.isRecording()).isTrue();
    }

    @Test
    void flushFailureClearsBufferToPreventRetryStorm() {
        doThrow(new RuntimeException("DB write failed"))
                .when(mockTickStore).saveBatch(any());

        recorder.start("Buffer Clear Test", "feed-1");

        // Submit exactly BATCH_SIZE ticks to trigger flush
        for (int i = 1; i <= SessionRecorder.BATCH_SIZE; i++) {
            recorder.onTick(createTick(i));
        }

        // Buffer should be empty — the failed batch was discarded
        assertThat(recorder.getBufferSize()).isEqualTo(0);
    }

    @Test
    void recordingContinuesAfterFlushFailure_newTicksBuffer() {
        doThrow(new RuntimeException("DB write failed"))
                .when(mockTickStore).saveBatch(any());

        recorder.start("Continue Test", "feed-1");

        // Trigger a failed flush
        for (int i = 1; i <= SessionRecorder.BATCH_SIZE; i++) {
            recorder.onTick(createTick(i));
        }

        // Now submit 5 more ticks — they should buffer normally
        for (int i = 101; i <= 105; i++) {
            recorder.onTick(createTick(i));
        }

        assertThat(recorder.getBufferSize()).isEqualTo(5);
        assertThat(recorder.isRecording()).isTrue();
    }

    @Test
    void tickCountIncludesDiscardedTicks_knownMetadataDivergence() {
        // THIS TEST DOCUMENTS A KNOWN LIMITATION:
        // tickCount is incremented in onTick() BEFORE flush, and is NOT
        // decremented on flush failure. After a flush failure, tickCount
        // will be higher than the actual number of persisted ticks.

        doThrow(new RuntimeException("DB write failed"))
                .when(mockTickStore).saveBatch(any());

        recorder.start("Metadata Divergence Test", "feed-1");

        for (int i = 1; i <= SessionRecorder.BATCH_SIZE; i++) {
            recorder.onTick(createTick(i));
        }

        // tickCount reflects all ticks seen, including those lost in the failed flush
        assertThat(recorder.getTickCount()).isEqualTo(SessionRecorder.BATCH_SIZE);

        // 0 ticks were actually persisted
        verify(mockTickStore, times(1)).saveBatch(any()); // flush was attempted
        // saveBatch threw — nothing was saved
    }

    @Test
    void stopMarksSessionAsFailedAfterPriorFlushFailure() {
        // First flush fails, second (on stop) succeeds — but flushFailed flag remains
        doThrow(new RuntimeException("DB write failed"))
                .doNothing()
                .when(mockTickStore).saveBatch(any());

        recorder.start("Stop After Failure Test", "feed-1");

        // Trigger the failed flush
        for (int i = 1; i <= SessionRecorder.BATCH_SIZE; i++) {
            recorder.onTick(createTick(i));
        }

        // Add a few more ticks, then stop
        for (int i = 101; i <= 105; i++) {
            recorder.onTick(createTick(i));
        }

        Session stopped = recorder.stop();

        // Session must be FAILED — a prior flush lost ticks, data is incomplete
        assertThat(stopped.getStatus()).isEqualTo(Session.Status.FAILED);
        // tickCount includes the 100 lost + 5 saved = 105 (known divergence)
        assertThat(stopped.getTickCount()).isEqualTo(105);
        assertThat(recorder.isRecording()).isFalse();
        verify(mockSessionStore).updateStatus(eq(1L), eq(Session.Status.FAILED));
    }

    @Test
    void stopCompletesSessionWhenNoFlushFailure() {
        recorder.start("Clean Session", "feed-1");

        for (int i = 1; i <= 5; i++) {
            recorder.onTick(createTick(i));
        }

        Session completed = recorder.stop();

        assertThat(completed.getStatus()).isEqualTo(Session.Status.COMPLETED);
        verify(mockSessionStore, never()).updateStatus(anyLong(), any());
    }

    @Test
    void destroyFlushesBufferOnShutdown() {
        recorder.start("Destroy Test", "feed-1");

        // Buffer some ticks (below batch threshold)
        for (int i = 1; i <= 10; i++) {
            recorder.onTick(createTick(i));
        }
        assertThat(recorder.getBufferSize()).isEqualTo(10);

        recorder.destroy();

        // flush was called during destroy — saveBatch should have been invoked
        verify(mockTickStore, times(1)).saveBatch(any());
    }

    @Test
    void destroyFlushFailureDoesNotThrow() {
        doThrow(new RuntimeException("DB dead on shutdown"))
                .when(mockTickStore).saveBatch(any());

        recorder.start("Destroy Failure Test", "feed-1");
        for (int i = 1; i <= 10; i++) {
            recorder.onTick(createTick(i));
        }

        // destroy() should not throw even if flush fails
        recorder.destroy();

        assertThat(recorder.getBufferSize()).isEqualTo(0);
    }

    // --- Helper ---

    private Tick createTick(long seqNum) {
        return new Tick("BTCUSDT", new BigDecimal("45000.00"), new BigDecimal("1"),
                seqNum, Instant.now(), "test-feed");
    }
}
