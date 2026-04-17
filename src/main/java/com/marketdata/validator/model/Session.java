package com.marketdata.validator.model;

import java.time.Instant;

/**
 * A recording session — metadata for a batch of captured ticks.
 * Maps to the `sessions` table in SQLite.
 *
 * Blueprint Section 5.1:
 *   - id: auto-increment
 *   - name: human label ("btc-morning-session")
 *   - feedId: which connection was recorded
 *   - status: RECORDING → COMPLETED or FAILED
 *   - startedAt / endedAt: time range
 *   - tickCount / byteSize: stats
 */
public class Session {

    public enum Status {
        RECORDING, COMPLETED, FAILED
    }

    private long id;
    private String name;
    private String feedId;
    private Status status;
    private Instant startedAt;
    private Instant endedAt; // null while recording
    private long tickCount;
    private long byteSize;

    public Session() {
    }

    public Session(String name, String feedId) {
        this.name = name;
        this.feedId = feedId;
        this.status = Status.RECORDING;
        this.startedAt = Instant.now();
        this.tickCount = 0;
        this.byteSize = 0;
    }

    // --- Getters and Setters ---

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFeedId() {
        return feedId;
    }

    public void setFeedId(String feedId) {
        this.feedId = feedId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public long getTickCount() {
        return tickCount;
    }

    public void setTickCount(long tickCount) {
        this.tickCount = tickCount;
    }

    public long getByteSize() {
        return byteSize;
    }

    public void setByteSize(long byteSize) {
        this.byteSize = byteSize;
    }
}
