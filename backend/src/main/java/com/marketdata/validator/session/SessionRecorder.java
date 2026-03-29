package com.marketdata.validator.session;

import com.marketdata.validator.feed.FeedManager;
import com.marketdata.validator.model.Session;
import com.marketdata.validator.model.Tick;
import com.marketdata.validator.store.SessionStore;
import com.marketdata.validator.store.TickStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Records live ticks to SQLite during a recording session.
 *
 * Blueprint Section 5.4:
 *   - Start recording: create session in DB, start saving every tick
 *   - Stop recording: finalize session, compute summary stats
 *   - Tick buffer: batch-insert every 100 ticks or every 1 second
 *     (whichever comes first) to avoid a DB write per tick
 *
 * Usage:
 *   Register as a FeedManager global tick listener.
 *   Call start() to begin recording, stop() to finalize.
 *   onTick() is called by FeedManager for every incoming tick.
 */
@Component
public class SessionRecorder {

    private static final Logger log = LoggerFactory.getLogger(SessionRecorder.class);
    static final int BATCH_SIZE = 100;
    static final long FLUSH_INTERVAL_MS = 1_000;

    private final SessionStore sessionStore;
    private final TickStore tickStore;

    private final ReentrantLock lock = new ReentrantLock();
    private final List<Tick> buffer = new ArrayList<>();

    private volatile boolean recording;
    private Session currentSession;
    private long tickCount;
    private long byteSize;
    private long lastFlushTime;

    public SessionRecorder(SessionStore sessionStore, TickStore tickStore, FeedManager feedManager) {
        this.sessionStore = sessionStore;
        this.tickStore = tickStore;
        feedManager.addGlobalTickListener(this::onTick);
    }

    /**
     * Start a new recording session.
     *
     * @param name   human-readable session name (e.g., "btc-morning-session")
     * @param feedId which feed connection to record from
     * @return the created Session with its auto-generated ID
     * @throws IllegalStateException if already recording
     */
    public Session start(String name, String feedId) {
        lock.lock();
        try {
            if (recording) {
                throw new IllegalStateException("Already recording session: " + currentSession.getId());
            }

            Session session = new Session();
            session.setName(name);
            session.setFeedId(feedId);
            session.setStatus(Session.Status.RECORDING);
            session.setStartedAt(Instant.now());

            currentSession = sessionStore.create(session);
            tickCount = 0;
            byteSize = 0;
            buffer.clear();
            lastFlushTime = System.currentTimeMillis();
            recording = true;

            log.info("Started recording session id={} name='{}' feed={}",
                    currentSession.getId(), name, feedId);
            return currentSession;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called for every incoming tick (registered as FeedManager global listener).
     * Buffers ticks and flushes when batch is full or time interval exceeded.
     */
    public void onTick(Tick tick) {
        if (!recording) {
            return;
        }

        lock.lock();
        try {
            if (!recording) {
                return; // Double-check after acquiring lock
            }

            tick.setSessionId(currentSession.getId());
            buffer.add(tick);
            tickCount++;
            byteSize += estimateTickSize(tick);

            if (buffer.size() >= BATCH_SIZE || isFlushDue()) {
                flush();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stop the current recording session. Flushes remaining buffer
     * and finalizes the session in the database.
     *
     * @return the finalized Session
     * @throws IllegalStateException if not currently recording
     */
    public Session stop() {
        lock.lock();
        try {
            if (!recording) {
                throw new IllegalStateException("Not currently recording");
            }

            // Final flush of remaining ticks
            flush();

            // Finalize session in DB
            sessionStore.complete(currentSession.getId(), tickCount, byteSize);

            // Update local state
            currentSession.setStatus(Session.Status.COMPLETED);
            currentSession.setEndedAt(Instant.now());
            currentSession.setTickCount(tickCount);
            currentSession.setByteSize(byteSize);

            Session completed = currentSession;
            recording = false;
            currentSession = null;

            log.info("Stopped recording session id={} ticks={} bytes={}",
                    completed.getId(), tickCount, byteSize);
            return completed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Whether a recording session is currently active.
     */
    public boolean isRecording() {
        return recording;
    }

    /**
     * The current session being recorded (null if not recording).
     */
    public Session getCurrentSession() {
        return currentSession;
    }

    /**
     * Number of ticks recorded in the current session.
     */
    public long getTickCount() {
        return tickCount;
    }

    /**
     * Number of ticks currently buffered (not yet flushed to DB).
     */
    public int getBufferSize() {
        lock.lock();
        try {
            return buffer.size();
        } finally {
            lock.unlock();
        }
    }

    @PreDestroy
    void destroy() {
        if (recording) {
            log.info("SessionRecorder shutting down — flushing {} buffered ticks", buffer.size());
            lock.lock();
            try {
                flush();
            } catch (Exception e) {
                log.error("Failed to flush ticks on shutdown: {}", e.getMessage());
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Force flush any buffered ticks to the database.
     * Useful for testing or manual control.
     */
    public void flushNow() {
        lock.lock();
        try {
            flush();
        } finally {
            lock.unlock();
        }
    }

    // --- Internal ---

    private void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        try {
            tickStore.saveBatch(new ArrayList<>(buffer));
            buffer.clear();
        } catch (Exception e) {
            log.error("Failed to flush {} ticks to DB — discarding to avoid retry storm: {}",
                    buffer.size(), e.getMessage());
            buffer.clear();
        }
        lastFlushTime = System.currentTimeMillis();
    }

    private boolean isFlushDue() {
        return System.currentTimeMillis() - lastFlushTime >= FLUSH_INTERVAL_MS;
    }

    /**
     * Rough estimate of a tick's byte size for session stats.
     * Counts string lengths of key fields — not exact, but consistent.
     */
    private long estimateTickSize(Tick tick) {
        long size = 8; // overhead
        size += tick.getSymbol() != null ? tick.getSymbol().length() : 0;
        size += tick.getPrice() != null ? tick.getPrice().toPlainString().length() : 0;
        size += tick.getFeedId() != null ? tick.getFeedId().length() : 0;
        size += 8 + 8; // timestamps (2 longs)
        size += 8; // sequenceNum
        return size;
    }
}
