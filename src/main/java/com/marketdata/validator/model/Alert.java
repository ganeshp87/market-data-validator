package com.marketdata.validator.model;

import java.time.Instant;

public class Alert {

    public enum Severity { INFO, WARN, CRITICAL }

    private long id;
    private String area;
    private Severity severity;
    private String message;
    private boolean acknowledged;
    private Instant createdAt;

    public Alert() {
        this.createdAt = Instant.now();
    }

    public Alert(String area, Severity severity, String message) {
        this();
        this.area = area;
        this.severity = severity;
        this.message = message;
    }

    // --- Getters and Setters ---

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }

    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean isAcknowledged() { return acknowledged; }
    public void setAcknowledged(boolean acknowledged) { this.acknowledged = acknowledged; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
