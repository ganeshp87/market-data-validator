package com.marketdata.validator.session;

import com.marketdata.validator.model.Session;
import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.session.SessionReplayer.ReplayProgress;
import com.marketdata.validator.session.SessionReplayer.State;
import com.marketdata.validator.store.SessionStore;
import com.marketdata.validator.store.TickStore;
import com.marketdata.validator.validator.ValidatorEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionReplayerTest {

    @Mock TickStore tickStore;
    @Mock SessionStore sessionStore;
    @Mock ValidatorEngine engine;

    SessionReplayer replayer;

    @BeforeEach
    void setUp() {
        replayer = new SessionReplayer(tickStore, sessionStore, engine);
    }

    // ── Helpers ──────────────────────────────────────────────

    private Session completedSession(long id) {
        Session s = new Session("test-session", "feed-1");
        s.setId(id);
        s.setStatus(Session.Status.COMPLETED);
        return s;
    }

    private Session recordingSession(long id) {
        Session s = new Session("test-session", "feed-1");
        s.setId(id);
        return s; // status defaults to RECORDING
    }

    private Tick tick(String symbol, long seq, Instant ts) {
        return new Tick(symbol, new BigDecimal("100.00"), new BigDecimal("1"), seq, ts, "feed-1");
    }

    /** Creates zero-gap ticks (1ms apart → 0ms sleep at 10x). */
    private List<Tick> zeroGapTicks(int count) {
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        List<Tick> ticks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ticks.add(tick("BTCUSDT", i + 1, base.plusMillis(i)));
        }
        return ticks;
    }

    /** Creates ticks with significant gaps between them. */
    private List<Tick> gappedTicks(int count, long gapMs) {
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        List<Tick> ticks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ticks.add(tick("BTCUSDT", i + 1, base.plusMillis(i * gapMs)));
        }
        return ticks;
    }

    private void setupSession(long id, List<Tick> ticks) {
        when(sessionStore.findById(id)).thenReturn(Optional.of(completedSession(id)));
        when(tickStore.findBySessionId(id)).thenReturn(ticks);
    }

    private void waitForState(State expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (replayer.getState() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    // ── Validation: start() preconditions ────────────────────

    @Test
    void start_sessionNotFound_throws() {
        when(sessionStore.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> replayer.start(99L, 1.0));
    }

    @Test
    void start_sessionStillRecording_throws() {
        when(sessionStore.findById(1L)).thenReturn(Optional.of(recordingSession(1)));
        assertThrows(IllegalArgumentException.class, () -> replayer.start(1L, 1.0));
    }

    @Test
    void start_sessionNoTicks_throws() {
        when(sessionStore.findById(1L)).thenReturn(Optional.of(completedSession(1)));
        when(tickStore.findBySessionId(1L)).thenReturn(Collections.emptyList());
        assertThrows(IllegalArgumentException.class, () -> replayer.start(1L, 1.0));
    }

    @Test
    void start_speedZero_throws() {
        assertThrows(IllegalArgumentException.class, () -> replayer.start(1L, 0));
    }

    @Test
    void start_speedNegative_throws() {
        assertThrows(IllegalArgumentException.class, () -> replayer.start(1L, -2.0));
    }

    @Test
    void start_alreadyReplaying_throws() throws InterruptedException {
        List<Tick> ticks = gappedTicks(100, 500); // long replay
        setupSession(1L, ticks);

        replayer.start(1L, 1.0);
        try {
            assertThrows(IllegalStateException.class, () -> replayer.start(2L, 1.0));
        } finally {
            replayer.stop();
        }
    }

    @Test
    void start_alreadyPaused_throws() throws InterruptedException {
        List<Tick> ticks = gappedTicks(100, 500);
        setupSession(1L, ticks);

        replayer.start(1L, 1.0);
        replayer.pause();
        try {
            assertThrows(IllegalStateException.class, () -> replayer.start(2L, 1.0));
        } finally {
            replayer.stop();
        }
    }

    // ── State transitions ────────────────────────────────────

    @Test
    void start_setsStateToReplaying() {
        setupSession(1L, zeroGapTicks(5));
        replayer.start(1L, 10.0);
        // State is REPLAYING immediately (set before thread starts)
        assertTrue(replayer.getState() == State.REPLAYING || replayer.getState() == State.COMPLETED);
        replayer.stop();
    }

    @Test
    void start_setsReplaySessionId() {
        setupSession(42L, zeroGapTicks(3));
        replayer.start(42L, 10.0);
        assertEquals(42L, replayer.getReplaySessionId());
        replayer.stop();
    }

    @Test
    void start_resetsEngine() {
        setupSession(1L, zeroGapTicks(3));
        replayer.start(1L, 10.0);
        verify(engine).reset();
        replayer.stop();
    }

    @Test
    void start_setsTotalTicks() {
        setupSession(1L, zeroGapTicks(7));
        replayer.start(1L, 10.0);
        assertEquals(7, replayer.getTotalTicks());
        replayer.stop();
    }

    @Test
    void pause_whenIdle_noOp() {
        replayer.pause();
        assertEquals(State.IDLE, replayer.getState());
    }

    @Test
    void resume_whenIdle_noOp() {
        replayer.resume();
        assertEquals(State.IDLE, replayer.getState());
    }

    @Test
    void pause_setsStateToPaused() {
        List<Tick> ticks = gappedTicks(50, 200);
        setupSession(1L, ticks);

        replayer.start(1L, 1.0);
        replayer.pause();
        assertEquals(State.PAUSED, replayer.getState());
        replayer.stop();
    }

    @Test
    void resume_afterPause_setsReplaying() {
        List<Tick> ticks = gappedTicks(50, 200);
        setupSession(1L, ticks);

        replayer.start(1L, 1.0);
        replayer.pause();
        replayer.resume();
        assertEquals(State.REPLAYING, replayer.getState());
        replayer.stop();
    }

    @Test
    void stop_setsStateToIdle() {
        setupSession(1L, zeroGapTicks(5));
        replayer.start(1L, 10.0);
        replayer.stop();
        assertEquals(State.IDLE, replayer.getState());
    }

    // ── Replay behavior (async) ──────────────────────────────

    @Test
    void replay_feedsAllTicksToEngine() throws InterruptedException {
        List<Tick> ticks = zeroGapTicks(10);
        setupSession(1L, ticks);

        replayer.start(1L, 10.0);
        waitForState(State.COMPLETED, 5000);

        verify(engine, times(10)).onTick(any(Tick.class));
    }

    @Test
    void replay_feedsTicksInOrder() throws InterruptedException {
        List<Tick> ticks = zeroGapTicks(5);
        setupSession(1L, ticks);

        ArgumentCaptor<Tick> captor = ArgumentCaptor.forClass(Tick.class);
        replayer.start(1L, 10.0);
        waitForState(State.COMPLETED, 5000);

        verify(engine, times(5)).onTick(captor.capture());
        List<Tick> fed = captor.getAllValues();
        for (int i = 0; i < fed.size(); i++) {
            assertEquals(i + 1, fed.get(i).getSequenceNum());
        }
    }

    @Test
    void replay_completesState() throws InterruptedException {
        setupSession(1L, zeroGapTicks(3));
        replayer.start(1L, 10.0);
        waitForState(State.COMPLETED, 5000);
        assertEquals(State.COMPLETED, replayer.getState());
    }

    @Test
    void replay_ticksReplayedMatchesTotal() throws InterruptedException {
        setupSession(1L, zeroGapTicks(8));
        replayer.start(1L, 10.0);
        waitForState(State.COMPLETED, 5000);
        assertEquals(8, replayer.getTicksReplayed());
    }

    @Test
    void replay_progressListenerCalledPerTick() throws InterruptedException {
        setupSession(1L, zeroGapTicks(5));

        CopyOnWriteArrayList<ReplayProgress> events = new CopyOnWriteArrayList<>();
        replayer.setProgressListener(events::add);

        replayer.start(1L, 10.0);
        waitForState(State.COMPLETED, 5000);

        // 5 ticks + 1 COMPLETED notification = 6
        assertEquals(6, events.size());
    }

    @Test
    void replay_progressListenerFinalEventIsCompleted() throws InterruptedException {
        setupSession(1L, zeroGapTicks(3));

        CopyOnWriteArrayList<ReplayProgress> events = new CopyOnWriteArrayList<>();
        replayer.setProgressListener(events::add);

        replayer.start(1L, 10.0);
        waitForState(State.COMPLETED, 5000);

        ReplayProgress last = events.get(events.size() - 1);
        assertEquals(State.COMPLETED, last.state());
        assertEquals(3, last.ticksReplayed());
        assertEquals(3, last.totalTicks());
    }

    @Test
    void replay_progressTicksIncrementMonotonically() throws InterruptedException {
        setupSession(1L, zeroGapTicks(5));

        CopyOnWriteArrayList<ReplayProgress> events = new CopyOnWriteArrayList<>();
        replayer.setProgressListener(events::add);

        replayer.start(1L, 10.0);
        waitForState(State.COMPLETED, 5000);

        int prev = 0;
        for (ReplayProgress p : events) {
            assertTrue(p.ticksReplayed() >= prev, "Ticks should increase monotonically");
            prev = p.ticksReplayed();
        }
    }

    // ── Speed control ────────────────────────────────────────

    @Test
    void setSpeed_updatesSpeedFactor() {
        replayer.setSpeed(5.0);
        assertEquals(5.0, replayer.getSpeedFactor());
    }

    @Test
    void setSpeed_zeroThrows() {
        assertThrows(IllegalArgumentException.class, () -> replayer.setSpeed(0));
    }

    @Test
    void setSpeed_negativeThrows() {
        assertThrows(IllegalArgumentException.class, () -> replayer.setSpeed(-1.0));
    }

    @Test
    void start_configuresSpeedFactor() {
        setupSession(1L, zeroGapTicks(3));
        replayer.start(1L, 5.0);
        assertEquals(5.0, replayer.getSpeedFactor());
        replayer.stop();
    }

    @Test
    void higherSpeed_completesInLessTime() throws InterruptedException {
        // 5 ticks with 100ms gaps
        List<Tick> ticks = gappedTicks(5, 100);
        setupSession(1L, ticks);
        // Use a very high speed factor (no need for lenient()) 
        when(sessionStore.findById(2L)).thenReturn(Optional.of(completedSession(2)));
        when(tickStore.findBySessionId(2L)).thenReturn(new ArrayList<>(ticks));

        // 1x speed
        long start1 = System.currentTimeMillis();
        replayer.start(1L, 1.0);
        waitForState(State.COMPLETED, 5000);
        long elapsed1 = System.currentTimeMillis() - start1;

        // Reset for second run
        replayer = new SessionReplayer(tickStore, sessionStore, engine);

        // 10x speed
        long start2 = System.currentTimeMillis();
        replayer.start(2L, 10.0);
        waitForState(State.COMPLETED, 5000);
        long elapsed2 = System.currentTimeMillis() - start2;

        assertTrue(elapsed2 < elapsed1, "10x replay should be faster than 1x");
    }

    // ── getResults delegation ────────────────────────────────

    @Test
    void getResults_delegatesToEngine() {
        List<ValidationResult> mockResults = List.of(
                new ValidationResult(ValidationResult.Area.ACCURACY, ValidationResult.Status.PASS,
                        "ok", 0.0, 0.0));
        when(engine.getResults()).thenReturn(mockResults);

        List<ValidationResult> results = replayer.getResults();
        assertSame(mockResults, results);
        verify(engine).getResults();
    }

    // ── Stop mid-replay ──────────────────────────────────────

    @Test
    void stop_midReplay_haltsBeforeAllTicksProcessed() throws InterruptedException {
        // Use ticks with 200ms gaps at 1x — gives time to stop mid-replay
        List<Tick> ticks = gappedTicks(20, 200);
        setupSession(1L, ticks);

        CountDownLatch firstTick = new CountDownLatch(1);
        doAnswer(inv -> {
            firstTick.countDown();
            return null;
        }).when(engine).onTick(any());

        replayer.start(1L, 1.0);
        assertTrue(firstTick.await(5, TimeUnit.SECONDS));

        replayer.stop();
        // Give thread time to exit
        Thread.sleep(100);

        assertTrue(replayer.getTicksReplayed() < 20,
                "Should stop before processing all ticks");
    }

    // ── Pause + resume live behavior ─────────────────────────

    @Test
    void pauseResume_continuesReplayToCompletion() throws InterruptedException {
        List<Tick> ticks = gappedTicks(10, 50);
        setupSession(1L, ticks);

        CountDownLatch someTicks = new CountDownLatch(3);
        doAnswer(inv -> {
            someTicks.countDown();
            return null;
        }).when(engine).onTick(any());

        replayer.start(1L, 1.0);
        assertTrue(someTicks.await(5, TimeUnit.SECONDS));

        replayer.pause();
        assertEquals(State.PAUSED, replayer.getState());

        // Allow thread to settle into paused wait
        Thread.sleep(100);
        int ticksAtPause = replayer.getTicksReplayed();
        assertTrue(ticksAtPause >= 3 && ticksAtPause < 10,
                "Some ticks processed before pause, but not all");
        Thread.sleep(200); // verify no progress while paused
        assertEquals(ticksAtPause, replayer.getTicksReplayed());

        replayer.resume();
        waitForState(State.COMPLETED, 10000);

        assertEquals(State.COMPLETED, replayer.getState());
        assertEquals(10, replayer.getTicksReplayed());
    }

    // ── Restart after completion ─────────────────────────────

    @Test
    void afterCompletion_canStartNewReplay() throws InterruptedException {
        setupSession(1L, zeroGapTicks(3));

        replayer.start(1L, 10.0);
        waitForState(State.COMPLETED, 5000);
        assertEquals(State.COMPLETED, replayer.getState());

        // Should be able to start a new replay
        setupSession(2L, zeroGapTicks(5));
        replayer.start(2L, 10.0);
        waitForState(State.COMPLETED, 5000);

        assertEquals(2L, replayer.getReplaySessionId());
        assertEquals(5, replayer.getTicksReplayed());
    }

    // ── Edge cases ───────────────────────────────────────────

    @Test
    void replay_singleTickCompletesSuccessfully() throws InterruptedException {
        setupSession(1L, zeroGapTicks(1));

        replayer.start(1L, 10.0);
        waitForState(State.COMPLETED, 5000);

        assertEquals(1, replayer.getTicksReplayed());
        assertEquals(State.COMPLETED, replayer.getState());
        verify(engine, times(1)).onTick(any(Tick.class));
    }

    @Test
    void replay_allSameTimestampsCompletesQuickly() throws InterruptedException {
        Instant sameTime = Instant.parse("2024-01-01T00:00:00Z");
        List<Tick> ticks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ticks.add(tick("BTCUSDT", i + 1, sameTime));
        }
        setupSession(1L, ticks);

        long start = System.currentTimeMillis();
        replayer.start(1L, 1.0);
        waitForState(State.COMPLETED, 5000);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(10, replayer.getTicksReplayed());
        assertTrue(elapsed < 2000, "Same-timestamp ticks should replay instantly");
    }

    @Test
    void stop_whenIdleIsNoOp() {
        replayer.stop();
        assertEquals(State.IDLE, replayer.getState());
    }
}
