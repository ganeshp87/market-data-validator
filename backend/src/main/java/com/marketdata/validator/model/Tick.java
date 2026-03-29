package com.marketdata.validator.model;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A single market data tick — one price update from an exchange feed.
 * This is the core data unit that flows through the entire system:
 * Feed → ValidatorEngine → SessionRecorder → SSE → Browser.
 */
public class Tick {

    private long id;
    private String symbol;
    private BigDecimal price;
    private BigDecimal bid;
    private BigDecimal ask;
    private BigDecimal volume;
    private long sequenceNum;
    private Instant exchangeTimestamp;
    private Instant receivedTimestamp;
    private String feedId;
    private Long sessionId;
    private String correlationId;
    private String traceId;

    public Tick() {
    }

    public Tick(String symbol, BigDecimal price, BigDecimal volume,
                long sequenceNum, Instant exchangeTimestamp, String feedId) {
        this.symbol = symbol;
        this.price = price;
        this.volume = volume;
        this.sequenceNum = sequenceNum;
        this.exchangeTimestamp = exchangeTimestamp;
        this.receivedTimestamp = Instant.now();
        this.feedId = feedId;
        this.correlationId = UUID.randomUUID().toString();
    }

    /**
     * Latency = receivedTimestamp - exchangeTimestamp.
     * Returns Duration.ZERO if either timestamp is null.
     */
    public Duration getLatency() {
        if (exchangeTimestamp == null || receivedTimestamp == null) {
            return Duration.ZERO;
        }
        return Duration.between(exchangeTimestamp, receivedTimestamp);
    }

    /**
     * Latency in milliseconds — convenience for validators and UI.
     * Clamped to 0 minimum to handle clock skew between local machine and exchange servers.
     */
    public long getLatencyMs() {
        return Math.max(0, getLatency().toMillis());
    }

    // --- Getters and Setters ---

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getBid() {
        return bid;
    }

    public void setBid(BigDecimal bid) {
        this.bid = bid;
    }

    public BigDecimal getAsk() {
        return ask;
    }

    public void setAsk(BigDecimal ask) {
        this.ask = ask;
    }

    public BigDecimal getVolume() {
        return volume;
    }

    public void setVolume(BigDecimal volume) {
        this.volume = volume;
    }

    public long getSequenceNum() {
        return sequenceNum;
    }

    public void setSequenceNum(long sequenceNum) {
        this.sequenceNum = sequenceNum;
    }

    public Instant getExchangeTimestamp() {
        return exchangeTimestamp;
    }

    public void setExchangeTimestamp(Instant exchangeTimestamp) {
        this.exchangeTimestamp = exchangeTimestamp;
    }

    public Instant getReceivedTimestamp() {
        return receivedTimestamp;
    }

    public void setReceivedTimestamp(Instant receivedTimestamp) {
        this.receivedTimestamp = receivedTimestamp;
    }

    public String getFeedId() {
        return feedId;
    }

    public void setFeedId(String feedId) {
        this.feedId = feedId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
