package com.marketdata.validator.session;

import com.marketdata.validator.model.Session;
import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.store.SessionStore;
import com.marketdata.validator.store.TickStore;
import com.marketdata.validator.validator.ValidatorEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Replays a recorded session's ticks through the ValidatorEngine at configurable speed.
 * Preserves original inter-tick timing gaps scaled by speed factor.
 */
@Component
public class SessionReplayer {

    private static final Logger log = LoggerFactory.getLogger(SessionReplayer.class);

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
    private volatile Consumer<ReplayProgress> progressListener;

    public SessionReplayer(TickStore tickStore, SessionStore sessionStore, ValidatorEngine engine) {
        this.tickStore = tickStore;
        this.sessionStore = sessionStore;
        this.engine = engine;
    }

    public record ReplayProgress(State state, int ticksReplayed, int totalTicks, double speedFactor) {}

    public void start(long sessionId, double speed) {
        if (state == State.REPLAYING || state == State.PAUSED) {
            throw new IllegalStateException("Replay already in progress");
        }
        if (speed <= 0) {
            throw new IllegalArgumentException("Speed must be positive");
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
                if (state == State.IDLE) break;

                synchronized (pauseLock) {
                    while (state == State.PAUSED) {
                        pauseLock.wait();
                    }
                }
                if (state == State.IDLE) break;

                // Respect inter-tick timing scaled by speed factor
                if (prevTs != null && tick.getExchangeTimestamp() != null) {
                    long gapMs = Duration.between(prevTs, tick.getExchangeTimestamp()).toMillis();
                    if (gapMs > 0) {
                        long sleepMs = (long) (gapMs / speedFactor);
                        if (sleepMs > 0) {
                            Thread.sleep(sleepMs);
                        }
                    }
                }

                engine.onTick(tick);
                ticksReplayed.incrementAndGet();
                prevTs = tick.getExchangeTimestamp();

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
        if (speed <= 0) {
            throw new IllegalArgumentException("Speed must be positive");
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
        this.progressListener = listener;
    }

    private void notifyProgress() {
        Consumer<ReplayProgress> listener = this.progressListener;
        if (listener != null) {
            listener.accept(new ReplayProgress(state, ticksReplayed.get(), totalTicks, speedFactor));
        }
    }
}
