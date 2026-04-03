package com.marketdata.validator.simulator;

import com.marketdata.validator.feed.FeedAdapter;
import com.marketdata.validator.model.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * FeedAdapter implementation for the internal LVWR_T simulator.
 *
 * Unlike external adapters (Binance, Finnhub), this adapter never connects to
 * a real WebSocket. The LVWRChaosSimulator calls buildTick(...) directly to
 * produce Tick objects, bypassing the subscribe/parse/heartbeat lifecycle.
 *
 * The parseTick(String) and isHeartbeat(String) methods are implemented for
 * completeness with the interface contract, but are not used at runtime.
 *
 * Blueprint Section 2 — LVWR_T Runtime:
 *   "Simulator output is broadcast through the same FeedManager listener path used
 *    by external feeds, so the validator and SSE pipeline exercise the real runtime path."
 */
public class LVWRSimulatorAdapter implements FeedAdapter {

    private static final Logger log = LoggerFactory.getLogger(LVWRSimulatorAdapter.class);

    @Override
    public String getSubscribeMessage(List<String> symbols) {
        // No WebSocket subscription needed — simulator runs internally
        return "";
    }

    @Override
    public String getUnsubscribeMessage(List<String> symbols) {
        return "";
    }

    /**
     * Not used at runtime — LVWRChaosSimulator calls buildTick() directly.
     * Returns null to signal that raw string messages are not processed by this adapter.
     */
    @Override
    public Tick parseTick(String rawMessage) {
        return null;
    }

    @Override
    public boolean isHeartbeat(String rawMessage) {
        return rawMessage != null && rawMessage.startsWith("#");
    }

    /**
     * Build a Tick from already-computed values. Called by LVWRChaosSimulator.
     *
     * Uses a single captured clock to prevent negative latency (Blueprint Section 2):
     *   received = Instant.now()
     *   exchange = received - syntheticLatencyMs  (always BEFORE received)
     *
     * @param feedId         connection ID of the LVWR_T feed
     * @param symbol         instrument symbol (e.g. "1", "126")
     * @param price          trade price (BigDecimal, always > 0)
     * @param volume         trade quantity
     * @param seqNum         sequence number
     * @param syntheticLatencyMs  how far before now to place exchangeTimestamp (2–20 ms normally)
     * @param failureType    null for clean ticks; the injected failure type otherwise
     */
    public Tick buildTick(String feedId, String symbol, BigDecimal price,
                          BigDecimal volume, long seqNum, long syntheticLatencyMs,
                          FailureType failureType) {
        // Single captured clock — exchange is ALWAYS before received
        Instant received = Instant.now();
        Instant exchange = received.minusMillis(syntheticLatencyMs);

        Tick tick = new Tick();
        tick.setFeedId(feedId);
        tick.setSymbol(symbol);
        tick.setPrice(price);
        tick.setVolume(volume);
        tick.setSequenceNum(seqNum);
        tick.setReceivedTimestamp(received);
        tick.setExchangeTimestamp(exchange);
        tick.setCorrelationId(UUID.randomUUID().toString());
        tick.setSource("LVWR_T");

        if (failureType != null) {
            tick.setFailureType(failureType.name());
        }

        return tick;
    }

    /**
     * Build a stale-timestamp tick (STALE_TIMESTAMP failure type).
     * Exchange timestamp is intentionally 5 seconds behind receive time.
     */
    public Tick buildStaleTick(String feedId, String symbol, BigDecimal price,
                               BigDecimal volume, long seqNum) {
        Instant received = Instant.now();
        Instant exchange = received.minusSeconds(5); // intentional 5-second staleness

        Tick tick = new Tick();
        tick.setFeedId(feedId);
        tick.setSymbol(symbol);
        tick.setPrice(price);
        tick.setVolume(volume);
        tick.setSequenceNum(seqNum);
        tick.setReceivedTimestamp(received);
        tick.setExchangeTimestamp(exchange);
        tick.setCorrelationId(UUID.randomUUID().toString());
        tick.setSource("LVWR_T");
        tick.setFailureType(FailureType.STALE_TIMESTAMP.name());

        return tick;
    }
}
