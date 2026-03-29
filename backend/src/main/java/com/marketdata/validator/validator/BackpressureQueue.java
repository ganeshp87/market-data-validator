package com.marketdata.validator.validator;

import com.marketdata.validator.feed.FeedManager;
import com.marketdata.validator.model.Tick;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded queue between feed ingestion and the ValidatorEngine.
 *
 * Blueprint Section 5.6 — Backpressure Strategy:
 *   - Bounded queue between feed ingestion and validator engine
 *   - If incoming rate exceeds processing capacity, applies backpressure
 *   - Drop policy: oldest ticks evicted when queue full (configurable)
 *   - Metrics emitted on drop count for alerting
 *
 * Data flow:
 *   FeedManager → BackpressureQueue.submit() → [bounded queue] → consumer thread → ValidatorEngine.onTick()
 *
 * The consumer thread drains ticks one-at-a-time to preserve ordering.
 * This decouples the WebSocket ingestion thread from the (potentially slower)
 * validation pipeline, preventing slow validators from stalling the feed.
 */
@Component
public class BackpressureQueue {

    private static final Logger log = LoggerFactory.getLogger(BackpressureQueue.class);
    static final int DEFAULT_CAPACITY = 10_000;

    /**
     * What happens when the queue is full:
     * - DROP_OLDEST: evict the head (oldest tick), add the new one → preserves freshness
     * - DROP_NEWEST: reject the new tick → preserves history
     */
    public enum DropPolicy { DROP_OLDEST, DROP_NEWEST }

    private final ValidatorEngine engine;
    private final ArrayBlockingQueue<Tick> queue;
    private final DropPolicy dropPolicy;
    private final AtomicLong droppedCount = new AtomicLong(0);
    private final AtomicLong totalSubmitted = new AtomicLong(0);
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private volatile boolean running = true;
    private final Thread consumerThread;

    // Lock object for the DROP_OLDEST poll+offer pair to prevent concurrent race
    private final Object dropLock = new Object();

    /**
     * Spring-wired constructor. Registers as a FeedManager global tick listener
     * so that all incoming ticks flow through this queue before reaching validators.
     */
    @Autowired
    public BackpressureQueue(FeedManager feedManager, ValidatorEngine engine) {
        this(engine, DEFAULT_CAPACITY, DropPolicy.DROP_OLDEST);
        feedManager.addGlobalTickListener(this::submit);
    }

    /**
     * Test constructor — no FeedManager registration.
     * Allows unit tests to call submit() directly without mocking FeedManager.
     */
    BackpressureQueue(ValidatorEngine engine, int capacity, DropPolicy dropPolicy) {
        this.engine = engine;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.dropPolicy = dropPolicy;

        this.consumerThread = new Thread(this::consumeLoop, "backpressure-consumer");
        this.consumerThread.setDaemon(true);
        this.consumerThread.start();

        log.info("BackpressureQueue started: {} {} {}",
                StructuredArguments.keyValue("capacity", capacity),
                StructuredArguments.keyValue("policy", dropPolicy),
                StructuredArguments.keyValue("event", "queue_started"));
    }

    /**
     * Submit a tick into the bounded queue.
     * Called from the WebSocket ingestion thread (via FeedManager listener).
     *
     * If the queue is full, the drop policy determines what happens:
     * - DROP_OLDEST: poll (remove head) + offer (add new) — O(1) amortized
     * - DROP_NEWEST: silently discard the incoming tick — O(1)
     *
     * Never blocks the caller — critical for non-blocking ingestion.
     */
    public void submit(Tick tick) {
        totalSubmitted.incrementAndGet();

        if (dropPolicy == DropPolicy.DROP_NEWEST) {
            if (!queue.offer(tick)) {
                droppedCount.incrementAndGet();
                log.trace("Queue full — dropped newest tick {} {}",
                        StructuredArguments.keyValue("seq", tick.getSequenceNum()),
                        StructuredArguments.keyValue("event", "tick_dropped"));
            }
        } else {
            // DROP_OLDEST (default)
            // Synchronize the entire offer-or-drop sequence so a concurrent
            // thread's initial offer() cannot steal a slot freed by our poll(),
            // which would silently lose the evicted tick without counting it.
            synchronized (dropLock) {
                if (!queue.offer(tick)) {
                    queue.poll(); // Evict oldest
                    queue.offer(tick); // Add new (guaranteed to succeed with lock held)
                    droppedCount.incrementAndGet();
                    log.trace("Queue full — dropped oldest tick, added {} {}",
                            StructuredArguments.keyValue("seq", tick.getSequenceNum()),
                            StructuredArguments.keyValue("event", "tick_dropped"));
                }
            }
        }
    }

    /**
     * Consumer loop — runs on a dedicated daemon thread.
     * Drains ticks one-at-a-time to preserve strict ordering.
     * Uses poll with timeout to avoid busy-spinning when the queue is empty.
     */
    private void consumeLoop() {
        while (running) {
            try {
                Tick tick = queue.poll(100, TimeUnit.MILLISECONDS);
                if (tick != null) {
                    try {
                        engine.onTick(tick);
                        totalProcessed.incrementAndGet();
                    } catch (Exception e) {
                        log.error("ValidatorEngine threw on tick {} {} {}",
                                StructuredArguments.keyValue("seq", tick.getSequenceNum()),
                                StructuredArguments.keyValue("event", "validation_error"),
                                StructuredArguments.keyValue("error", e.getMessage()), e);
                        // Don't stop consuming — one bad tick shouldn't kill the pipeline
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("BackpressureQueue consumer stopped");
    }

    // ── Metrics ─────────────────────────────────────────

    /** Total ticks submitted to the queue (before any drops). */
    public long getTotalSubmitted() { return totalSubmitted.get(); }

    /** Total ticks successfully processed by ValidatorEngine. */
    public long getTotalProcessed() { return totalProcessed.get(); }

    /** Total ticks dropped due to backpressure. */
    public long getDroppedCount() { return droppedCount.get(); }

    /** Current number of ticks waiting in the queue. */
    public int getQueueSize() { return queue.size(); }

    /** Maximum capacity of the queue. */
    public int getCapacity() { return queue.remainingCapacity() + queue.size(); }

    /** The active drop policy. */
    public DropPolicy getDropPolicy() { return dropPolicy; }

    /** Whether the consumer thread is still running. */
    public boolean isRunning() { return running && consumerThread.isAlive(); }

    // ── Lifecycle ───────────────────────────────────────

    /** Gracefully stop the consumer thread. */
    @PreDestroy
    public void shutdown() {
        running = false;
        consumerThread.interrupt();
        log.info("BackpressureQueue shutdown initiated");
    }

    /** Clear the queue and reset all metrics. */
    public void reset() {
        queue.clear();
        droppedCount.set(0);
        totalSubmitted.set(0);
        totalProcessed.set(0);
    }
}
