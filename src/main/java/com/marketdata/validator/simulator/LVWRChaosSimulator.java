package com.marketdata.validator.simulator;

import com.marketdata.validator.model.Tick;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Internal LVWR_T synthetic market data simulator.
 *
 * Blueprint Section 2 — LVWR Chaos Simulator:
 *   - 18-instrument universe (17 tradable + aggregate 126)
 *   - 4 modes: CLEAN, NOISY, CHAOS, SCENARIO
 *   - 12 failure types configurable via ScenarioConfig
 *   - Deadline-based tick pacing (stable throughput)
 *   - Single captured clock per tick (no negative latency)
 *   - Runs on a virtual thread; started by FeedManager
 *
 * Deterministic phase-1 emission order:
 *   126, 1, 2, 26, 20, 55, 8, 12, 54, 13, 14, 16, 19, 78, 9, 24, 23, 17
 *
 * Sequence number design (Fix 1):
 *   Each instrument owns its own seqNum counter (starts at 0; first real tick = 1).
 *   Heartbeats are emitted with seqNum=0 so validators see a clean 0→1 transition.
 *   This ensures the CompletenessValidator never detects false gaps in CLEAN mode.
 */
public class LVWRChaosSimulator implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(LVWRChaosSimulator.class);
    private static final MathContext MC = new MathContext(8, RoundingMode.HALF_UP);

    // Deterministic emission order — blueprint-specified
    static final int[] EMISSION_ORDER = {126, 1, 2, 26, 20, 55, 8, 12, 54, 13, 14, 16, 19, 78, 9, 24, 23, 17};

    // Instrument universe: id → InstrumentState
    private final Map<Integer, InstrumentState> instruments = new LinkedHashMap<>();

    private final String feedId;
    private final LVWRSimulatorAdapter adapter;
    private final ScenarioEngine scenarioEngine;
    private final Consumer<Tick> tickConsumer;

    private final AtomicReference<ScenarioConfig> config = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong ticksSent = new AtomicLong(0);
    /** Counted down to zero in the run() finally block — used by waitForStop(). */
    private final CountDownLatch stoppedLatch = new CountDownLatch(1);
    /** Reference to the virtual thread running this simulator — set at start of run(). */
    private final AtomicReference<Thread> runnerThread = new AtomicReference<>();
    /** Per-type failure injection counts — keyed by FailureType name */
    private final Map<String, AtomicLong> failureCountsByType = new ConcurrentHashMap<>();

    // Reconnect storm tracking
    private int disconnectCount = 0;
    private long disconnectWindowStart = 0;

    public LVWRChaosSimulator(String feedId, ScenarioConfig config, Consumer<Tick> tickConsumer) {
        this(feedId, config, tickConsumer, new ScenarioEngine());
    }

    /** Package-private constructor for testing — allows injecting a deterministic ScenarioEngine. */
    LVWRChaosSimulator(String feedId, ScenarioConfig config, Consumer<Tick> tickConsumer, ScenarioEngine scenarioEngine) {
        this.feedId = feedId;
        this.config.set(config);
        this.adapter = new LVWRSimulatorAdapter();
        this.scenarioEngine = scenarioEngine;
        this.tickConsumer = tickConsumer;
        // Pre-populate all 12 failure type counters at zero
        for (FailureType ft : FailureType.values()) {
            failureCountsByType.put(ft.name(), new AtomicLong(0));
        }
        initInstruments();
    }

    @Override
    public void run() {
        runnerThread.set(Thread.currentThread());
        running.set(true);
        scenarioEngine.reset();

        log.info("LVWR_T simulator starting {} {} {}",
                StructuredArguments.keyValue("feedId", feedId),
                StructuredArguments.keyValue("mode", config.get().getMode()),
                StructuredArguments.keyValue("ticksPerSecond", config.get().getTicksPerSecond()));

        // Phase 1: Emit pre-open reference rows for each instrument
        emitPhase1HeartbeatsIfEnabled();

        long tradeCount = 0;
        int numTrades = config.get().getNumTrades(); // 0 = unlimited
        long intervalNanos = 1_000_000_000L / config.get().getTicksPerSecond();
        long nextDeadlineNs = System.nanoTime();

        try {
            while (running.get()) {
                if (numTrades > 0 && tradeCount >= numTrades) {
                    break;
                }

                // Pick the next instrument in round-robin order (skip 126 — it's aggregate)
                int instrId = EMISSION_ORDER[(int) (tradeCount % (EMISSION_ORDER.length - 1)) + 1]; // skip index 0 = 126
                InstrumentState state = instruments.get(instrId);

                // Decide whether to inject a failure
                FailureType failure = scenarioEngine.decide(config.get());

                // Generate the tick (or handle special failure modes)
                if (failure == FailureType.DISCONNECT) {
                    handleDisconnect();
                    tradeCount++;
                    ticksSent.incrementAndGet();
                    failureCountsByType.get(FailureType.DISCONNECT.name()).incrementAndGet();
                } else if (failure == FailureType.RECONNECT_STORM) {
                    handleReconnectStorm();
                    tradeCount++;
                    ticksSent.incrementAndGet();
                    failureCountsByType.get(FailureType.RECONNECT_STORM.name()).incrementAndGet();
                } else if (failure == FailureType.THROTTLE_BURST) {
                    handleThrottleBurst(state);
                    tradeCount++;
                    failureCountsByType.get(FailureType.THROTTLE_BURST.name()).incrementAndGet();
                } else {
                    Tick tick = buildTick(state, instrId, failure);
                    // Count the failure even when the tick is null (e.g. MALFORMED_PAYLOAD)
                    if (failure != null) failureCountsByType.get(failure.name()).incrementAndGet();
                    if (tick != null) {
                        tickConsumer.accept(tick);
                        ticksSent.incrementAndGet();
                    }
                    tradeCount++;
                }

                // Deadline-based pacing — stable throughput regardless of processing jitter
                nextDeadlineNs += intervalNanos;
                long sleepNs = nextDeadlineNs - System.nanoTime();
                if (sleepNs > 0) {
                    Thread.sleep(0, (int) Math.min(sleepNs, 999_999));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running.set(false);
            stoppedLatch.countDown();
            log.info("LVWR_T simulator stopped {} {} {}",
                    StructuredArguments.keyValue("feedId", feedId),
                    StructuredArguments.keyValue("ticksSent", ticksSent.get()),
                    StructuredArguments.keyValue("totalFailures", getTotalFailuresInjected()));
        }
    }

    public void stop() {
        running.set(false);
        Thread t = runnerThread.get();
        if (t != null) t.interrupt(); // wake from any blocking sleep (e.g. DISCONNECT 8s)
    }

    /**
     * Block until the simulator's run() method exits, or until the timeout elapses.
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return true if the simulator stopped within the timeout, false if it timed out
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public boolean waitForStop(long timeoutMs) throws InterruptedException {
        return stoppedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getTicksSent() {
        return ticksSent.get();
    }

    /** Returns per-failure-type injection counts (all 12 types, zero if not injected). */
    public Map<String, Long> getFailuresInjected() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        for (FailureType ft : FailureType.values()) {
            snapshot.put(ft.name(), failureCountsByType.get(ft.name()).get());
        }
        return snapshot;
    }

    /** Returns total failures across all types (convenience for logging). */
    public long getTotalFailuresInjected() {
        return failureCountsByType.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public void updateConfig(ScenarioConfig newConfig) {
        ScenarioConfig old = this.config.get();
        boolean modeChanged = newConfig.getMode() != old.getMode();
        boolean scenarioTargetChanged = newConfig.getMode() == SimulatorMode.SCENARIO
                && newConfig.getTargetScenario() != old.getTargetScenario();

        // Reset per-type counters when the mode changes or when the SCENARIO target
        // switches — the caller selected a specific fault for isolated testing and old
        // counts from a prior mode run must not pollute the FAILURES INJECTED panel.
        // Reset BEFORE swapping the config so the run loop cannot increment a counter
        // using the new config before the reset takes effect.
        if (modeChanged || scenarioTargetChanged) {
            failureCountsByType.values().forEach(c -> c.set(0));
        }

        this.config.set(newConfig);
    }

    public ScenarioConfig getConfig() {
        return config.get();
    }

    // --- Internal tick construction ---

    /**
     * Build a tick for the given instrument and failure type.
     * Uses per-instrument sequence numbers so each symbol's seqNums are dense and
     * consecutive (1, 2, 3…), preventing false gap events in the CompletenessValidator.
     */
    private Tick buildTick(InstrumentState state, int instrId, FailureType failure) {
        BigDecimal price = state.nextPrice();
        BigDecimal volume = randomVolume();
        long syntheticLatencyMs = 2 + (long) (Math.random() * 18); // 2–19 ms

        String symbol = state.getSymbol();

        if (failure == null) {
            long seq = state.nextSeqNum();
            Tick t = adapter.buildTick(feedId, symbol, price, volume, seq, syntheticLatencyMs, null);
            state.setLastExchangeTimestamp(t.getExchangeTimestamp());
            return t;
        }

        return switch (failure) {
            case NEGATIVE_PRICE -> {
                BigDecimal negPrice = new BigDecimal("-0.125");
                long seq = state.nextSeqNum();
                Tick t = adapter.buildTick(feedId, symbol, negPrice, volume, seq, syntheticLatencyMs, failure);
                state.setLastExchangeTimestamp(t.getExchangeTimestamp());
                yield t;
            }
            case PRICE_SPIKE -> {
                BigDecimal spikePrice = price.multiply(new BigDecimal("1.14"), MC); // +14%
                state.setLastPrice(spikePrice);
                long seq = state.nextSeqNum();
                Tick t = adapter.buildTick(feedId, symbol, spikePrice, volume, seq, syntheticLatencyMs, failure);
                state.setLastExchangeTimestamp(t.getExchangeTimestamp());
                yield t;
            }
            case SEQUENCE_GAP -> {
                // Advance the per-symbol counter by 5 extra to create a detectable gap.
                // The CompletenessValidator will see seqNum jump by 6 (5 skipped + 1 new),
                // recording 5 missing seqNums per gap event. This keeps the missing count
                // proportional to the fault injection rate (Fix 5).
                state.advanceSeqNum(5);
                long seq = state.nextSeqNum();
                Tick t = adapter.buildTick(feedId, symbol, price, volume, seq, syntheticLatencyMs, failure);
                state.setLastExchangeTimestamp(t.getExchangeTimestamp());
                yield t;
            }
            case DUPLICATE_TICK -> {
                long seq = state.nextSeqNum();
                Tick orig = adapter.buildTick(feedId, symbol, price, volume, seq, syntheticLatencyMs, failure);
                state.setLastExchangeTimestamp(orig.getExchangeTimestamp());
                tickConsumer.accept(orig); // emit original
                ticksSent.incrementAndGet();
                yield adapter.buildTick(feedId, symbol, price, volume, seq, syntheticLatencyMs, failure); // duplicate
            }
            case OUT_OF_ORDER -> {
                // Use an ascending seqNum so the idempotency guard in validators does not
                // discard the tick. The out-of-order condition is signalled by setting the
                // exchange timestamp to be BEFORE the previous tick's exchange timestamp for
                // this symbol — exactly what OrderingValidator checks.
                long seq = state.nextSeqNum();
                Tick t = adapter.buildTick(feedId, symbol, price, volume, seq, syntheticLatencyMs, failure);
                Instant prevExchange = state.getLastExchangeTimestamp();
                if (prevExchange != null) {
                    t.setExchangeTimestamp(prevExchange.minusMillis(50));
                }
                // Do not update lastExchangeTimestamp — the next clean tick stays in order.
                yield t;
            }
            case STALE_TIMESTAMP -> {
                long seq = state.nextSeqNum();
                Tick t = adapter.buildStaleTick(feedId, symbol, price, volume, seq);
                state.setLastExchangeTimestamp(t.getExchangeTimestamp());
                yield t;
            }
            case MALFORMED_PAYLOAD -> null; // null tick — signals CompletenessValidator of missing data
            case SYMBOL_MISMATCH -> {
                // Aggregate instrument 126 with wrong source instrument ID
                long seq = state.nextSeqNum();
                yield adapter.buildTick(feedId, instruments.get(126).getSymbol(), price, volume, seq, syntheticLatencyMs, failure);
            }
            case CUMVOL_BACKWARD -> {
                state.decreaseCumVol(200);
                long seq = state.nextSeqNum();
                Tick t = adapter.buildTick(feedId, symbol, price, volume, seq, syntheticLatencyMs, failure);
                state.setLastExchangeTimestamp(t.getExchangeTimestamp());
                yield t;
            }
            default -> {
                long seq = state.nextSeqNum();
                Tick t = adapter.buildTick(feedId, symbol, price, volume, seq, syntheticLatencyMs, failure);
                state.setLastExchangeTimestamp(t.getExchangeTimestamp());
                yield t;
            }
        };
    }

    private void handleDisconnect() throws InterruptedException {
        log.info("LVWR_T injecting DISCONNECT {}",
                StructuredArguments.keyValue("feedId", feedId));
        // Pause emission for configured duration — ThroughputValidator will detect the drop
        Thread.sleep(config.get().getDisconnectDurationMs());
    }

    private void handleReconnectStorm() throws InterruptedException {
        long now = System.currentTimeMillis();
        if (disconnectWindowStart == 0 || now - disconnectWindowStart > 30_000) {
            disconnectWindowStart = now;
            disconnectCount = 0;
        }
        disconnectCount++;

        log.info("LVWR_T injecting RECONNECT_STORM {} {}",
                StructuredArguments.keyValue("feedId", feedId),
                StructuredArguments.keyValue("disconnectCount", disconnectCount));

        Thread.sleep(config.get().getReconnectPauseDurationMs()); // brief pause per reconnect event
    }

    private void handleThrottleBurst(InstrumentState state) {
        // Emit 800 ticks with the same timestamp — BackpressureQueue DROP_OLDEST kicks in.
        // Each instrument uses its own per-symbol seqNum so continuity is preserved.
        Instant burstTime = Instant.now();
        BigDecimal price = state.nextPrice();
        BigDecimal volume = randomVolume();
        for (int i = 0; i < 800; i++) {
            int eid = EMISSION_ORDER[(i % (EMISSION_ORDER.length - 1)) + 1];
            InstrumentState instrState = instruments.get(eid);
            long seq = instrState.nextSeqNum();
            Tick t = adapter.buildTick(feedId, instrState.getSymbol(), price, volume, seq, 2, FailureType.THROTTLE_BURST);
            t.setReceivedTimestamp(burstTime); // same timestamp for all burst ticks
            t.setExchangeTimestamp(burstTime.minusMillis(2));
            tickConsumer.accept(t);
            ticksSent.incrementAndGet();
        }
    }

    private void emitPhase1HeartbeatsIfEnabled() {
        if (!config.get().isIncludeHeartbeats()) return;
        for (int instrId : EMISSION_ORDER) {
            InstrumentState state = instruments.get(instrId);
            if (state == null) continue;
            BigDecimal price = state.getLastPrice();
            // Heartbeats use seqNum=0 (pre-open; real ticks start at seqNum=1 via nextSeqNum())
            Tick hbTick = adapter.buildTick(feedId, state.getSymbol(), price,
                    BigDecimal.ZERO, 0L, 5L, null);
            tickConsumer.accept(hbTick);
            ticksSent.incrementAndGet();
        }
    }

    private BigDecimal randomVolume() {
        // Rough log-normal distribution — mostly small lots
        double u = Math.random();
        int qty;
        if (u < 0.45) qty = 1 + (int) (Math.random() * 10);
        else if (u < 0.75) qty = 11 + (int) (Math.random() * 40);
        else if (u < 0.93) qty = 51 + (int) (Math.random() * 150);
        else if (u < 0.98) qty = 201 + (int) (Math.random() * 300);
        else qty = 500 + (int) (Math.random() * 500);
        return new BigDecimal(qty);
    }

    // --- Instrument universe setup ---

    private void initInstruments() {
        // Blueprint Table: ID, Symbol, Price Range, Volatility
        addInstrument(1,   "EUR/USD",     "1.1000", "0.0005");
        addInstrument(2,   "GBP/USD",     "1.2900", "0.0005");
        addInstrument(8,   "USD/JPY",     "150.00", "0.0008");
        addInstrument(9,   "EUR/GBP",     "0.8550", "0.0004");
        addInstrument(12,  "XAU/USD",     "20.25",  "0.003");
        addInstrument(13,  "LIBOR_ON",    "5.320",  "0.0002");
        addInstrument(14,  "EURIBOR_3M",  "3.875",  "0.002");
        addInstrument(16,  "UK_GILT_10Y", "4.225",  "0.002");
        addInstrument(17,  "US_TSY_10Y",  "4.325",  "0.002");
        addInstrument(19,  "DE_BUND_10Y", "2.325",  "0.002");
        addInstrument(20,  "FR_OAT_10Y",  "2.975",  "0.002");
        addInstrument(23,  "IT_BTP_10Y",  "3.675",  "0.003");
        addInstrument(24,  "JP_JGB_10Y",  "1.350",  "0.001");
        addInstrument(26,  "SP_BONO_10Y", "3.125",  "0.002");
        addInstrument(54,  "US_TSY_2Y",   "4.750",  "0.002");
        addInstrument(55,  "US_TSY_30Y",  "4.575",  "0.002");
        addInstrument(78,  "CHF_LIBOR_3M","1.425",  "0.0002");
        addInstrument(126, "AGGR_INDEX",  "100.00", "0.001");
    }

    private void addInstrument(int id, String symbol, String seedPrice, String volatility) {
        instruments.put(id, new InstrumentState(symbol, new BigDecimal(seedPrice), new BigDecimal(volatility)));
    }

    // --- InstrumentState inner class ---

    static class InstrumentState {
        private final String symbol;
        private BigDecimal lastPrice;
        private final BigDecimal volatility;
        private BigDecimal cumVol = BigDecimal.ZERO;
        private final Random rng = new Random();

        /** Per-instrument sequence counter. Starts at 0; nextSeqNum() returns 1 on first call. */
        private long seqNum = 0;

        /** Last exchange timestamp emitted for this instrument — used by OUT_OF_ORDER injection. */
        private Instant lastExchangeTimestamp;

        InstrumentState(String symbol, BigDecimal seedPrice, BigDecimal volatility) {
            this.symbol = symbol;
            this.lastPrice = seedPrice;
            this.volatility = volatility;
        }

        /** GBM price walk with zero drift, per-instrument volatility, bounded to > 0. */
        BigDecimal nextPrice() {
            double z = rng.nextGaussian();
            BigDecimal move = volatility.multiply(BigDecimal.valueOf(z), MC);
            BigDecimal newPrice = lastPrice.add(lastPrice.multiply(move, MC));
            // Clamp to > 0
            if (newPrice.compareTo(BigDecimal.ZERO) <= 0) {
                newPrice = lastPrice;
            }
            lastPrice = newPrice.setScale(6, RoundingMode.HALF_UP);
            return lastPrice;
        }

        BigDecimal getLastPrice() { return lastPrice; }
        void setLastPrice(BigDecimal p) { this.lastPrice = p; }

        BigDecimal getCumVol() { return cumVol; }
        void addCumVol(BigDecimal vol) { cumVol = cumVol.add(vol); }
        void decreaseCumVol(long amount) { cumVol = cumVol.subtract(new BigDecimal(amount)); }

        String getSymbol() { return symbol; }

        /** Returns the next sequence number (first call returns 1). */
        long nextSeqNum() { return ++seqNum; }

        /**
         * Advance the seqNum counter by {@code extra} positions without emitting a tick.
         * Used by SEQUENCE_GAP injection to create a detectable gap.
         */
        void advanceSeqNum(long extra) { seqNum += extra; }

        Instant getLastExchangeTimestamp() { return lastExchangeTimestamp; }
        void setLastExchangeTimestamp(Instant ts) { this.lastExchangeTimestamp = ts; }
    }
}
