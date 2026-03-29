package com.marketdata.validator.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Configuration and runtime state for a single WebSocket feed connection.
 * Represents a connection to an exchange like Binance or Finnhub.
 */
public class Connection {

    public enum AdapterType {
        BINANCE, FINNHUB, GENERIC
    }

    public enum Status {
        CONNECTED, DISCONNECTED, RECONNECTING, ERROR
    }

    private String id;
    private String name;
    private String url;
    private AdapterType adapterType;
    private List<String> symbols;
    private Status status;
    private Instant connectedAt;
    private Instant lastTickAt;
    private long tickCount;

    public Connection() {
        this.id = UUID.randomUUID().toString();
        this.symbols = new ArrayList<>();
        this.status = Status.DISCONNECTED;
    }

    public Connection(String name, String url, AdapterType adapterType, List<String> symbols) {
        this();
        this.name = name;
        this.url = url;
        this.adapterType = adapterType;
        this.symbols = new ArrayList<>(symbols);
    }

    /**
     * Record that a tick was received — updates lastTickAt and increments count.
     */
    public void recordTick() {
        this.lastTickAt = Instant.now();
        this.tickCount++;
    }

    // --- Getters and Setters ---

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public AdapterType getAdapterType() {
        return adapterType;
    }

    public void setAdapterType(AdapterType adapterType) {
        this.adapterType = adapterType;
    }

    public List<String> getSymbols() {
        return symbols;
    }

    public void setSymbols(List<String> symbols) {
        this.symbols = new ArrayList<>(symbols);
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public void setConnectedAt(Instant connectedAt) {
        this.connectedAt = connectedAt;
    }

    public Instant getLastTickAt() {
        return lastTickAt;
    }

    public void setLastTickAt(Instant lastTickAt) {
        this.lastTickAt = lastTickAt;
    }

    public long getTickCount() {
        return tickCount;
    }

    public void setTickCount(long tickCount) {
        this.tickCount = tickCount;
    }
}
