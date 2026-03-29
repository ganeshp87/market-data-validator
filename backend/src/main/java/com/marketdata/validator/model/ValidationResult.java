package com.marketdata.validator.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * The result of one validator's assessment — e.g., "ACCURACY: PASS at 99.98%".
 * Each of the 8 validators produces one ValidationResult.
 * The ValidatorEngine aggregates all 8 into a ValidationSummary.
 */
public class ValidationResult {

    /**
     * The 8 testing areas from the blueprint.
     * Each validator maps to exactly one area.
     */
    public enum Area {
        ACCURACY,
        LATENCY,
        COMPLETENESS,
        RECONNECTION,
        THROUGHPUT,
        ORDERING,
        SUBSCRIPTION,
        STATEFUL
    }

    /**
     * Three-state outcome. Validators determine status by comparing
     * their metric against thresholds.
     */
    public enum Status {
        PASS, WARN, FAIL
    }

    private Area area;
    private Status status;
    private String message;
    private double metric;
    private double threshold;
    private Instant timestamp;
    private Map<String, Object> details;

    public ValidationResult() {
        this.timestamp = Instant.now();
        this.details = new HashMap<>();
    }

    public ValidationResult(Area area, Status status, String message,
                            double metric, double threshold) {
        this();
        this.area = area;
        this.status = status;
        this.message = message;
        this.metric = metric;
        this.threshold = threshold;
    }

    /**
     * Factory: create a PASS result.
     */
    public static ValidationResult pass(Area area, String message,
                                         double metric, double threshold) {
        return new ValidationResult(area, Status.PASS, message, metric, threshold);
    }

    /**
     * Factory: create a WARN result.
     */
    public static ValidationResult warn(Area area, String message,
                                         double metric, double threshold) {
        return new ValidationResult(area, Status.WARN, message, metric, threshold);
    }

    /**
     * Factory: create a FAIL result.
     */
    public static ValidationResult fail(Area area, String message,
                                         double metric, double threshold) {
        return new ValidationResult(area, Status.FAIL, message, metric, threshold);
    }

    // --- Getters and Setters ---

    public Area getArea() {
        return area;
    }

    public void setArea(Area area) {
        this.area = area;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public double getMetric() {
        return metric;
    }

    public void setMetric(double metric) {
        this.metric = metric;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }
}
