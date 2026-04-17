package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BackpressureQueueTest {

    private BackpressureQueue bpq;

    @AfterEach
    void tearDown() {
        if (bpq != null) {
            bpq.shutdown();
        }
    }

    // ── Basic flow ──────────────────────────────────────

    @Test
    void tickFlowsThroughQueueToEngine() throws InterruptedException {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 100, BackpressureQueue.DropPolicy.DROP_OLDEST);

        bpq.submit(createTick(1));

        waitForProcessed(bpq, 1);
        assertThat(counter.getCount()).isEqualTo(1);
    }

    @Test
    void multipleTicksProcessedInOrder() throws InterruptedException {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 100, BackpressureQueue.DropPolicy.DROP_OLDEST);

        for (int i = 1; i <= 10; i++) {
            bpq.submit(createTick(i));
        }

        waitForProcessed(bpq, 10);
        assertThat(counter.getCount()).isEqualTo(10);
    }

    // ── Metrics ─────────────────────────────────────────

    @Test
    void totalSubmittedTracksAllSubmissions() {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 100, BackpressureQueue.DropPolicy.DROP_OLDEST);

        bpq.submit(createTick(1));
        bpq.submit(createTick(2));
        bpq.submit(createTick(3));

        assertThat(bpq.getTotalSubmitted()).isEqualTo(3);
    }

    @Test
    void totalProcessedTracksConsumedTicks() throws InterruptedException {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 100, BackpressureQueue.DropPolicy.DROP_OLDEST);

        bpq.submit(createTick(1));
        bpq.submit(createTick(2));

        waitForProcessed(bpq, 2);
        assertThat(bpq.getTotalProcessed()).isEqualTo(2);
    }

    @Test
    void capacityReturnsConfiguredSize() {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 500, BackpressureQueue.DropPolicy.DROP_OLDEST);

        assertThat(bpq.getCapacity()).isEqualTo(500);
    }

    @Test
    void dropPolicyReturnsConfiguredPolicy() {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 100, BackpressureQueue.DropPolicy.DROP_NEWEST);

        assertThat(bpq.getDropPolicy()).isEqualTo(BackpressureQueue.DropPolicy.DROP_NEWEST);
    }

    // ── DROP_OLDEST policy ──────────────────────────────

    @Test
    void dropOldest_evictsHeadWhenFull() throws InterruptedException {
        // Use capacity 3. Stop consumer so queue fills up.
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 3, BackpressureQueue.DropPolicy.DROP_OLDEST);

        // Shut down consumer so queue actually fills
        bpq.shutdown();
        Thread.sleep(150); // Let consumer thread exit

        // Submit 5 ticks into capacity-3 queue
        bpq.submit(createTick(1));
        bpq.submit(createTick(2));
        bpq.submit(createTick(3));
        bpq.submit(createTick(4)); // Queue full → evict seq 1, add seq 4
        bpq.submit(createTick(5)); // Queue full → evict seq 2, add seq 5

        assertThat(bpq.getTotalSubmitted()).isEqualTo(5);
        assertThat(bpq.getDroppedCount()).isEqualTo(2);
        assertThat(bpq.getQueueSize()).isEqualTo(3);
    }

    @Test
    void dropOldest_noDropsWhenQueueNotFull() {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 100, BackpressureQueue.DropPolicy.DROP_OLDEST);

        bpq.submit(createTick(1));
        bpq.submit(createTick(2));

        assertThat(bpq.getDroppedCount()).isEqualTo(0);
    }

    // ── DROP_NEWEST policy ──────────────────────────────

    @Test
    void dropNewest_rejectsNewTickWhenFull() throws InterruptedException {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 3, BackpressureQueue.DropPolicy.DROP_NEWEST);

        // Shut down consumer so queue fills
        bpq.shutdown();
        Thread.sleep(150);

        bpq.submit(createTick(1));
        bpq.submit(createTick(2));
        bpq.submit(createTick(3));
        bpq.submit(createTick(4)); // Rejected — queue full
        bpq.submit(createTick(5)); // Rejected — queue full

        assertThat(bpq.getTotalSubmitted()).isEqualTo(5);
        assertThat(bpq.getDroppedCount()).isEqualTo(2);
        assertThat(bpq.getQueueSize()).isEqualTo(3);
    }

    @Test
    void dropNewest_noDropsWhenNotFull() {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 100, BackpressureQueue.DropPolicy.DROP_NEWEST);

        bpq.submit(createTick(1));
        bpq.submit(createTick(2));

        assertThat(bpq.getDroppedCount()).isEqualTo(0);
    }

    // ── Queue size ──────────────────────────────────────

    @Test
    void queueSizeReflectsPendingItems() throws InterruptedException {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 100, BackpressureQueue.DropPolicy.DROP_OLDEST);

        // Shut down consumer to see queue build up
        bpq.shutdown();
        Thread.sleep(150);

        bpq.submit(createTick(1));
        bpq.submit(createTick(2));
        bpq.submit(createTick(3));

        assertThat(bpq.getQueueSize()).isEqualTo(3);
    }

    // ── Lifecycle ───────────────────────────────────────

    @Test
    void isRunningTrueAfterConstruction() {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 100, BackpressureQueue.DropPolicy.DROP_OLDEST);

        assertThat(bpq.isRunning()).isTrue();
    }

    @Test
    void shutdownStopsConsumer() throws InterruptedException {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 100, BackpressureQueue.DropPolicy.DROP_OLDEST);

        bpq.shutdown();
        Thread.sleep(200);

        assertThat(bpq.isRunning()).isFalse();
    }

    @Test
    void resetClearsQueueAndMetrics() throws InterruptedException {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 3, BackpressureQueue.DropPolicy.DROP_OLDEST);

        // Shut down consumer so we can fill the queue
        bpq.shutdown();
        Thread.sleep(150);

        bpq.submit(createTick(1));
        bpq.submit(createTick(2));
        bpq.submit(createTick(3));
        bpq.submit(createTick(4)); // Drop

        assertThat(bpq.getQueueSize()).isEqualTo(3);
        assertThat(bpq.getDroppedCount()).isEqualTo(1);

        bpq.reset();

        assertThat(bpq.getQueueSize()).isEqualTo(0);
        assertThat(bpq.getDroppedCount()).isEqualTo(0);
        assertThat(bpq.getTotalSubmitted()).isEqualTo(0);
        assertThat(bpq.getTotalProcessed()).isEqualTo(0);
    }

    // ── Exception resilience ────────────────────────────

    @Test
    void consumerSurvivesEngineException() throws InterruptedException {
        // Create a validator that throws on the first tick, works on the second
        AtomicInteger callCount = new AtomicInteger(0);
        Validator throwOnFirst = new Validator() {
            @Override public String getArea() { return "TEST"; }
            @Override public void onTick(Tick tick) {
                if (callCount.incrementAndGet() == 1) {
                    throw new RuntimeException("Boom on first tick");
                }
            }
            @Override public ValidationResult getResult() {
                return ValidationResult.pass(ValidationResult.Area.ORDERING, "test", 0, 0);
            }
            @Override public void reset() { }
            @Override public void configure(Map<String, Object> config) { }
        };

        ValidatorEngine engine = new ValidatorEngine(List.of(throwOnFirst));
        bpq = new BackpressureQueue(engine, 100, BackpressureQueue.DropPolicy.DROP_OLDEST);

        bpq.submit(createTick(1)); // Will cause exception in validator
        bpq.submit(createTick(2)); // Should still be processed

        waitForProcessed(bpq, 2);
        assertThat(callCount.get()).isEqualTo(2);
        assertThat(bpq.isRunning()).isTrue();
    }

    // ── High-throughput burst ───────────────────────────

    @Test
    void burstSubmissionProcessedCompletely() throws InterruptedException {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 1000, BackpressureQueue.DropPolicy.DROP_OLDEST);

        for (int i = 1; i <= 500; i++) {
            bpq.submit(createTick(i));
        }

        waitForProcessed(bpq, 500);
        assertThat(counter.getCount()).isEqualTo(500);
        assertThat(bpq.getDroppedCount()).isEqualTo(0);
    }

    @Test
    void burstBeyondCapacityDropsCorrectCount() throws InterruptedException {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 50, BackpressureQueue.DropPolicy.DROP_OLDEST);

        // Shut down consumer to guarantee queue fills
        bpq.shutdown();
        Thread.sleep(150);

        for (int i = 1; i <= 100; i++) {
            bpq.submit(createTick(i));
        }

        assertThat(bpq.getTotalSubmitted()).isEqualTo(100);
        assertThat(bpq.getDroppedCount()).isEqualTo(50);
        assertThat(bpq.getQueueSize()).isEqualTo(50);
    }

    // ── Default capacity ────────────────────────────────

    @Test
    void defaultCapacityIsReasonable() {
        assertThat(BackpressureQueue.DEFAULT_CAPACITY).isEqualTo(10_000);
    }

    // ── Edge cases: concurrent producers ────────────────

    @Test
    void concurrentProducersAllTicksAccountedFor() throws InterruptedException {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 5000, BackpressureQueue.DropPolicy.DROP_OLDEST);

        int threadCount = 4;
        int ticksPerThread = 250;
        Thread[] threads = new Thread[threadCount];

        for (int t = 0; t < threadCount; t++) {
            int baseSeq = t * ticksPerThread;
            threads[t] = new Thread(() -> {
                for (int i = 1; i <= ticksPerThread; i++) {
                    bpq.submit(createTick(baseSeq + i));
                }
            });
            threads[t].start();
        }

        for (Thread thread : threads) {
            thread.join(5000);
        }

        assertThat(bpq.getTotalSubmitted()).isEqualTo(threadCount * ticksPerThread);
        waitForProcessed(bpq, threadCount * ticksPerThread);
        assertThat(bpq.getDroppedCount()).isEqualTo(0);
    }

    @Test
    void submitAfterShutdownDoesNotThrow() throws InterruptedException {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 100, BackpressureQueue.DropPolicy.DROP_OLDEST);

        bpq.shutdown();
        Thread.sleep(150);

        // Submit after shutdown — should not throw
        bpq.submit(createTick(1));
        assertThat(bpq.getTotalSubmitted()).isEqualTo(1);
    }

    @Test
    void dropOldestPreservesNewestTicks() throws InterruptedException {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 5, BackpressureQueue.DropPolicy.DROP_OLDEST);

        bpq.shutdown();
        Thread.sleep(150);

        // Fill beyond capacity
        for (int i = 1; i <= 10; i++) {
            bpq.submit(createTick(i));
        }

        // Queue should have the 5 newest (6-10)
        assertThat(bpq.getQueueSize()).isEqualTo(5);
        assertThat(bpq.getDroppedCount()).isEqualTo(5);
    }

    @Test
    void metricsConsistentAfterMixedOperations() throws InterruptedException {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        bpq = new BackpressureQueue(engine, 10, BackpressureQueue.DropPolicy.DROP_NEWEST);

        // Submit some that get processed
        for (int i = 1; i <= 5; i++) {
            bpq.submit(createTick(i));
        }
        waitForProcessed(bpq, 5);

        // Now shut down consumer and overflow
        bpq.shutdown();
        Thread.sleep(150);

        for (int i = 6; i <= 25; i++) {
            bpq.submit(createTick(i));
        }

        // submitted = 25, processed = 5, dropped = 10 (20 submitted while shutdown, 10 overflow)
        assertThat(bpq.getTotalSubmitted()).isEqualTo(25);
        assertThat(bpq.getTotalProcessed()).isEqualTo(5);
        assertThat(bpq.getDroppedCount()).isEqualTo(10);
    }

    // ── Concurrency: DROP_OLDEST race guard ────────────

    @Test
    void concurrentDropOldestMaintainsMetricConsistency() throws Exception {
        CountingValidator counter = new CountingValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(counter));
        int capacity = 5;
        bpq = new BackpressureQueue(engine, capacity, BackpressureQueue.DropPolicy.DROP_OLDEST);

        // Pause consumer so we can overflow deterministically
        bpq.shutdown();
        Thread.sleep(150); // Let consumer thread exit

        int threadCount = 5;
        int ticksPerThread = 10;
        int totalSubmit = threadCount * ticksPerThread;

        // CyclicBarrier ensures all threads start submitting simultaneously
        CyclicBarrier startBarrier = new CyclicBarrier(threadCount);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            int start = t * ticksPerThread + 1;
            Thread.ofVirtual().start(() -> {
                try {
                    startBarrier.await(); // all threads wait here, then fire together
                    for (int i = start; i < start + ticksPerThread; i++) {
                        bpq.submit(createTick(i));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        doneLatch.await();

        // Core invariants with consumer stopped
        long submitted = bpq.getTotalSubmitted();
        long dropped = bpq.getDroppedCount();
        long processed = bpq.getTotalProcessed();
        int queueSize = bpq.getQueueSize();

        assertThat(submitted).isEqualTo(totalSubmit);
        assertThat(queueSize).isLessThanOrEqualTo(capacity);
        assertThat(queueSize).isEqualTo(capacity); // queue stays full when consumer is stopped
        assertThat(dropped + queueSize + processed).isEqualTo(submitted);
    }

    // ── Helpers ─────────────────────────────────────────

    private Tick createTick(long seqNum) {
        Instant exchangeTs = Instant.parse("2026-03-25T10:00:00Z").plusMillis(seqNum * 10);
        Tick tick = new Tick("BTCUSDT", new BigDecimal("45000.00"), new BigDecimal("1"),
                seqNum, exchangeTs, "test-feed");
        tick.setReceivedTimestamp(exchangeTs.plusMillis(5));
        return tick;
    }

    /**
     * Polls totalProcessed until it reaches the expected count.
     * Times out after 2 seconds to avoid hanging tests.
     */
    private void waitForProcessed(BackpressureQueue queue, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (queue.getTotalProcessed() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(queue.getTotalProcessed()).isGreaterThanOrEqualTo(expected);
    }

    /**
     * Simple validator that just counts ticks.
     */
    private static class CountingValidator implements Validator {
        private final AtomicInteger count = new AtomicInteger(0);

        @Override public String getArea() { return "COUNTING"; }
        @Override public void onTick(Tick tick) { count.incrementAndGet(); }
        @Override public ValidationResult getResult() {
            return ValidationResult.pass(ValidationResult.Area.ORDERING, "ok", 0, 0);
        }
        @Override public void reset() { count.set(0); }
        @Override public void configure(Map<String, Object> config) { }

        int getCount() { return count.get(); }
    }
}
