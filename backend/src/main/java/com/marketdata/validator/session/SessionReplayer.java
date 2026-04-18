package com.marketdata.validator.session;

import com.marketdata.validator.model.Session;
import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.store.SessionStore;
import com.marketdata.validator.store.TickStore;
import com.marketdata.validator.validator.ValidatorEngine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Replays a recorded session's ticks through the ValidatorEngine at configurable speed.
 * Preserves original inter-tick timing gaps scaled by speed factor.
 */
@Component
public class SessionReplayer {

    private static final double MAX_REPLAY_SPEED = 1000.0;

    public enum State { IDLE, REPLAYING, PAUSED, COMPLETED, FAILED }

    private final TickStore tickStore;
    private final SessionStore sessionStore;
    private final ValidatorEngine engine;

    private volatile State state = State.IDLE;
    private volatile double speedFactor = 1.0;
    private volatile long replaySessionId;
    private final AtomicInteger ticksReplayed = new AtomicInteger(0);
    private volatile int totalTicks;

    private Thread replayThread;
    private final Object pauseLock = new Object();
    private final AtomicReference<Consumer<ReplayProgress>> progressListener = new AtomicReference<>();

    public SessionReplayer(TickStore tickStore, SessionStore sessionStore, ValidatorEngine engine) {
        this.tickStore = tickStore;
        this.sessionStore = sessionStore;
        this.engine = engine;
    }

    public record ReplayProgress(State state, int ticksReplayed, int totalTicks, double speedFactor) {}

    /**
     * Thrown by {@link #replaySync} when the engine raises an exception mid-replay.
     * Carries the number of ticks processed before the failure and the failing tick's symbol.
     */
    public static class ReplayException extends RuntimeException {
        private final int ticksProcessed;
        private final String failingSymbol;

        public ReplayException(String message, int ticksProcessed, String failingSymbol, Throwable cause) {
            super(message, cause);
            this.ticksProcessed = ticksProcessed;
            this.failingSymbol = failingSymbol;
        }

        public int getTicksProcessed() { return ticksProcessed; }
        public String getFailingSymbol() { return failingSymbol; }
    }

    /**
     * Synchronous replay for REST callers: validates speed, resets the engine, and
     * feeds every tick to the engine in the calling thread (no background thread).
     *
     * @param sessionId session to replay
     * @param speed     replay speed factor; must be in (0, 1000]
     * @return the full list of ticks replayed
     * @throws IllegalArgumentException if speed is out of range
     * @throws ReplayException          if the engine throws while processing a tick
     */
    public List<Tick> replaySync(long sessionId, double speed) {
        if (speed <= 0 || speed > MAX_REPLAY_SPEED) {
            throw new IllegalArgumentException(
                    "Replay speed must be between 0 and 1000. Use speed=1000 for maximum fast-forward.");
        }

        List<Tick> ticks = tickStore.findBySessionId(sessionId);
        engine.reset();

        int processed = 0;
        try {
            for (Tick tick : ticks) {
                engine.onTick(tick);
                processed++;
            }
        } catch (Exception e) {
            Tick failingTick = processed < ticks.size() ? ticks.get(processed) : null;
            String failingSymbol = failingTick != null ? failingTick.getFeedScopedSymbol() : "unknown";
            engine.reset();
            throw new ReplayException(e.getMessage(), processed, failingSymbol, e);
        }

        return ticks;
    }

    public void start(long sessionId, double speed) {
        if (state == State.REPLAYING || state == State.PAUSED) {
            throw new IllegalStateException("Replay already in progress");
        }
        if (speed <= 0 || speed > MAX_REPLAY_SPEED) {
            throw new IllegalArgumentException(
                    "Replay speed must be between 0 and 1000. Use speed=1000 for maximum fast-forward.");
        }

        Session session = sessionStore.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        if (session.getStatus() != Session.Status.COMPLETED) {
            throw new IllegalArgumentException("Can only replay completed sessions");
        }

        List<Tick> ticks = tickStore.findBySessionId(sessionId);
        if (ticks.isEmpty()) {
            throw new IllegalArgumentException("Session has no ticks");
        }

        this.replaySessionId = sessionId;
        this.speedFactor = speed;
        this.totalTicks = ticks.size();
        this.ticksReplayed.set(0);

        engine.reset();
        state = State.REPLAYING;

        replayThread = new Thread(() -> replayLoop(ticks), "session-replayer");
        replayThread.setDaemon(true);
        replayThread.start();
    }

    private void replayLoop(List<Tick> ticks) {
        try {
            Instant prevTs = null;
            for (Tick tick : ticks) {
                if (!checkAndWaitIfPaused()) break;
                prevTs = processTickWithTiming(tick, prevTs);
                notifyProgress();
            }
            if (state == State.REPLAYING) {
                state = State.COMPLETED;
                notifyProgress();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (state != State.IDLE) {
                state = State.FAILED;
            }
        }
    }

    private boolean checkAndWaitIfPaused() throws InterruptedException {
        if (state == State.IDLE) return false;
        synchronized (pauseLock) {
            while (state == State.PAUSED) {
                pauseLock.wait();
            }
        }
        return state != State.IDLE;
    }

    private Instant processTickWithTiming(Tick tick, Instant prevTs) throws InterruptedException {
        sleepForGap(prevTs, tick);
        engine.onTick(tick);
        ticksReplayed.incrementAndGet();
        return tick.getExchangeTimestamp();
    }

    private void sleepForGap(Instant prevTs, Tick tick) throws InterruptedException {
        if (prevTs == null || tick.getExchangeTimestamp() == null) return;
        long gapMs = Duration.between(prevTs, tick.getExchangeTimestamp()).toMillis();
        if (gapMs > 0) {
            long sleepMs = (long) (gapMs / speedFactor);
            if (sleepMs > 0) {
                Thread.sleep(sleepMs);
            }
        }
    }

    public void pause() {
        if (state == State.REPLAYING) {
            state = State.PAUSED;
        }
    }

    public void resume() {
        if (state == State.PAUSED) {
            state = State.REPLAYING;
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        }
    }

    public void stop() {
        state = State.IDLE;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        if (replayThread != null) {
            replayThread.interrupt();
        }
    }

    public void setSpeed(double speed) {
        if (speed <= 0 || speed > MAX_REPLAY_SPEED) {
            throw new IllegalArgumentException(
                    "Replay speed must be between 0 and 1000. Use speed=1000 for maximum fast-forward.");
        }
        this.speedFactor = speed;
    }

    public State getState() { return state; }
    public int getTicksReplayed() { return ticksReplayed.get(); }
    public int getTotalTicks() { return totalTicks; }
    public double getSpeedFactor() { return speedFactor; }
    public long getReplaySessionId() { return replaySessionId; }
    public List<ValidationResult> getResults() { return engine.getResults(); }

    public void setProgressListener(Consumer<ReplayProgress> listener) {
        this.progressListener.set(listener);
    }

    private void notifyProgress() {
        Consumer<ReplayProgress> listener = this.progressListener.get();
        if (listener != null) {
            listener.accept(new ReplayProgress(state, ticksReplayed.get(), totalTicks, speedFactor));
        }
    }
}
