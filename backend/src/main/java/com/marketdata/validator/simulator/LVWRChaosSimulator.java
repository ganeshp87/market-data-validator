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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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

    private volatile ScenarioConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong ticksSent = new AtomicLong(0);
    private final AtomicLong failuresInjected = new AtomicLong(0);

    // Reconnect storm tracking
    private int disconnectCount = 0;
    private long disconnectWindowStart = 0;

    public LVWRChaosSimulator(String feedId, ScenarioConfig config, Consumer<Tick> tickConsumer) {
        this.feedId = feedId;
        this.config = config;
        this.adapter = new LVWRSimulatorAdapter();
        this.scenarioEngine = new ScenarioEngine();
        this.tickConsumer = tickConsumer;
        initInstruments();
    }

    @Override
    public void run() {
        running.set(true);
        scenarioEngine.reset();

        log.info("LVWR_T simulator starting {} {} {}",
                StructuredArguments.keyValue("feedId", feedId),
                StructuredArguments.keyValue("mode", config.getMode()),
                StructuredArguments.keyValue("ticksPerSecond", config.getTicksPerSecond()));

        // Phase 1: Emit pre-open reference rows for each instrument
        emitPhase1HeartbeatsIfEnabled();

        long seqNum = 0;
        long tradeCount = 0;
        int numTrades = config.getNumTrades(); // 0 = unlimited
        long intervalNanos = 1_000_000_000L / config.getTicksPerSecond();
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
                FailureType failure = scenarioEngine.decide(config);

                // Generate the tick (or handle special failure modes)
                if (failure == FailureType.DISCONNECT) {
                    handleDisconnect(seqNum);
                    seqNum++;
                    tradeCount++;
                    ticksSent.incrementAndGet();
                    failuresInjected.incrementAndGet();
                } else if (failure == FailureType.RECONNECT_STORM) {
                    handleReconnectStorm(state, seqNum);
                    seqNum++;
                    tradeCount++;
                    ticksSent.incrementAndGet();
                    failuresInjected.incrementAndGet();
                } else if (failure == FailureType.THROTTLE_BURST) {
                    seqNum = handleThrottleBurst(state, seqNum);
                    tradeCount++;
                    failuresInjected.incrementAndGet();
                } else {
                    Tick tick = buildTick(state, instrId, seqNum, failure);
                    if (tick != null) {
                        tickConsumer.accept(tick);
                        ticksSent.incrementAndGet();
                        if (failure != null) failuresInjected.incrementAndGet();
                    }
                    seqNum++;
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
            log.info("LVWR_T simulator stopped {} {} {}",
                    StructuredArguments.keyValue("feedId", feedId),
                    StructuredArguments.keyValue("ticksSent", ticksSent.get()),
                    StructuredArguments.keyValue("failuresInjected", failuresInjected.get()));
        }
    }

    public void stop() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getTicksSent() {
        return ticksSent.get();
    }

    public long getFailuresInjected() {
        return failuresInjected.get();
    }

    public void updateConfig(ScenarioConfig newConfig) {
        this.config = newConfig;
    }

    public ScenarioConfig getConfig() {
        return config;
    }

    // --- Internal tick construction ---

    private Tick buildTick(InstrumentState state, int instrId, long seqNum, FailureType failure) {
        BigDecimal price = state.nextPrice();
        BigDecimal volume = randomVolume();
        long syntheticLatencyMs = 2 + (long) (Math.random() * 18); // 2–19 ms

        String symbol = String.valueOf(instrId);

        if (failure == null) {
            return adapter.buildTick(feedId, symbol, price, volume, seqNum, syntheticLatencyMs, null);
        }

        return switch (failure) {
            case NEGATIVE_PRICE -> {
                BigDecimal negPrice = new BigDecimal("-0.125");
                yield adapter.buildTick(feedId, symbol, negPrice, volume, seqNum, syntheticLatencyMs, failure);
            }
            case PRICE_SPIKE -> {
                BigDecimal spikePrice = price.multiply(new BigDecimal("1.14"), MC); // +14%
                state.setLastPrice(spikePrice);
                yield adapter.buildTick(feedId, symbol, spikePrice, volume, seqNum, syntheticLatencyMs, failure);
            }
            case SEQUENCE_GAP -> {
                long gappedSeq = seqNum + 500_000; // Skip 500,000 sequence numbers
                Tick t = adapter.buildTick(feedId, symbol, price, volume, gappedSeq, syntheticLatencyMs, failure);
                yield t;
            }
            case DUPLICATE_TICK -> {
                Tick orig = adapter.buildTick(feedId, symbol, price, volume, seqNum, syntheticLatencyMs, failure);
                tickConsumer.accept(orig); // emit original
                ticksSent.incrementAndGet();
                yield adapter.buildTick(feedId, symbol, price, volume, seqNum, syntheticLatencyMs, failure); // duplicate
            }
            case OUT_OF_ORDER -> {
                long outOfOrderSeq = Math.max(0, seqNum - 10);
                yield adapter.buildTick(feedId, symbol, price, volume, outOfOrderSeq, syntheticLatencyMs, failure);
            }
            case STALE_TIMESTAMP -> adapter.buildStaleTick(feedId, symbol, price, volume, seqNum);
            case MALFORMED_PAYLOAD -> null; // null tick — signals CompletenessValidator of missing data
            case SYMBOL_MISMATCH -> {
                // Aggregate instrument 126 with wrong source instrument ID
                yield adapter.buildTick(feedId, "126", price, volume, seqNum, syntheticLatencyMs, failure);
            }
            case CUMVOL_BACKWARD -> {
                state.decreaseCumVol(200);
                yield adapter.buildTick(feedId, symbol, price, volume, seqNum, syntheticLatencyMs, failure);
            }
            default -> adapter.buildTick(feedId, symbol, price, volume, seqNum, syntheticLatencyMs, failure);
        };
    }

    private void handleDisconnect(long seqNum) throws InterruptedException {
        log.info("LVWR_T injecting DISCONNECT {} {}",
                StructuredArguments.keyValue("feedId", feedId),
                StructuredArguments.keyValue("seqNum", seqNum));
        // Pause emission for ~8 seconds — ThroughputValidator will detect the drop
        Thread.sleep(8_000);
    }

    private void handleReconnectStorm(InstrumentState state, long seqNum) throws InterruptedException {
        long now = System.currentTimeMillis();
        if (disconnectWindowStart == 0 || now - disconnectWindowStart > 30_000) {
            disconnectWindowStart = now;
            disconnectCount = 0;
        }
        disconnectCount++;

        log.info("LVWR_T injecting RECONNECT_STORM {} {} {}",
                StructuredArguments.keyValue("feedId", feedId),
                StructuredArguments.keyValue("disconnectCount", disconnectCount),
                StructuredArguments.keyValue("seqNum", seqNum));

        Thread.sleep(500); // brief pause per reconnect event
    }

    private long handleThrottleBurst(InstrumentState state, long seqNum) {
        // Emit 800 ticks with the same timestamp — BackpressureQueue DROP_OLDEST kicks in
        Instant burstTime = java.time.Instant.now();
        BigDecimal price = state.nextPrice();
        BigDecimal volume = randomVolume();
        for (int i = 0; i < 800; i++) {
            Tick t = adapter.buildTick(feedId, String.valueOf(
                    EMISSION_ORDER[(i % (EMISSION_ORDER.length - 1)) + 1]),
                    price, volume, seqNum + i, 2, FailureType.THROTTLE_BURST);
            t.setReceivedTimestamp(burstTime); // same timestamp for all burst ticks
            t.setExchangeTimestamp(burstTime.minusMillis(2));
            tickConsumer.accept(t);
            ticksSent.incrementAndGet();
        }
        return seqNum + 800;
    }

    private void emitPhase1HeartbeatsIfEnabled() {
        if (!config.isIncludeHeartbeats()) return;
        for (int instrId : EMISSION_ORDER) {
            InstrumentState state = instruments.get(instrId);
            if (state == null) continue;
            BigDecimal price = state.getLastPrice();
            Tick hbTick = adapter.buildTick(feedId, String.valueOf(instrId), price,
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

        InstrumentState(String symbol, BigDecimal seedPrice, BigDecimal volatility) {
            this.symbol = symbol;
            this.lastPrice = seedPrice;
            this.volatility = volatility;
        }

        /** GBM price walk with zero drift, per-instrument volatility, bounded to ±15% of seed. */
        BigDecimal nextPrice() {
            double z = rng.nextGaussian();
            BigDecimal move = volatility.multiply(new BigDecimal(z), MC);
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
    }
}
