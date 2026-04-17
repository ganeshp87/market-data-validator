package com.marketdata.validator.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyStatsTest {

    @Test
    void constructorSetsAllFields() {
        Instant start = Instant.parse("2026-03-23T10:00:00Z");
        Instant end = Instant.parse("2026-03-23T10:01:00Z");

        LatencyStats stats = new LatencyStats(12, 42, 95, 200, 3, 50.5, 10000, start, end);

        assertThat(stats.getP50Ms()).isEqualTo(12);
        assertThat(stats.getP95Ms()).isEqualTo(42);
        assertThat(stats.getP99Ms()).isEqualTo(95);
        assertThat(stats.getMaxMs()).isEqualTo(200);
        assertThat(stats.getMinMs()).isEqualTo(3);
        assertThat(stats.getAvgMs()).isEqualTo(50.5);
        assertThat(stats.getCount()).isEqualTo(10000);
        assertThat(stats.getWindowStart()).isEqualTo(start);
        assertThat(stats.getWindowEnd()).isEqualTo(end);
    }

    @Test
    void defaultConstructorLeavesFieldsAtDefaults() {
        LatencyStats stats = new LatencyStats();

        assertThat(stats.getP50Ms()).isEqualTo(0);
        assertThat(stats.getP95Ms()).isEqualTo(0);
        assertThat(stats.getP99Ms()).isEqualTo(0);
        assertThat(stats.getMaxMs()).isEqualTo(0);
        assertThat(stats.getMinMs()).isEqualTo(0);
        assertThat(stats.getAvgMs()).isEqualTo(0.0);
        assertThat(stats.getCount()).isEqualTo(0);
        assertThat(stats.getWindowStart()).isNull();
        assertThat(stats.getWindowEnd()).isNull();
    }

    @Test
    void percentilesRelationship_p50LessThanOrEqualP95LessThanOrEqualP99() {
        // A valid latency distribution always has p50 <= p95 <= p99 <= max
        LatencyStats stats = new LatencyStats(12, 42, 95, 200, 3, 40.0, 5000,
                Instant.now(), Instant.now());

        assertThat(stats.getP50Ms()).isLessThanOrEqualTo(stats.getP95Ms());
        assertThat(stats.getP95Ms()).isLessThanOrEqualTo(stats.getP99Ms());
        assertThat(stats.getP99Ms()).isLessThanOrEqualTo(stats.getMaxMs());
        assertThat(stats.getMinMs()).isLessThanOrEqualTo(stats.getP50Ms());
    }

    @Test
    void allSameLatency_allPercentilesEqual() {
        // Edge case: every tick has the same latency
        LatencyStats stats = new LatencyStats(10, 10, 10, 10, 10, 10.0, 1000,
                Instant.now(), Instant.now());

        assertThat(stats.getP50Ms()).isEqualTo(stats.getP95Ms());
        assertThat(stats.getP95Ms()).isEqualTo(stats.getP99Ms());
        assertThat(stats.getP99Ms()).isEqualTo(stats.getMaxMs());
        assertThat(stats.getMinMs()).isEqualTo(stats.getMaxMs());
    }

    @Test
    void settersAndGettersWorkForAllFields() {
        LatencyStats stats = new LatencyStats();
        Instant start = Instant.now();
        Instant end = start.plusSeconds(60);

        stats.setP50Ms(15);
        stats.setP95Ms(50);
        stats.setP99Ms(120);
        stats.setMaxMs(500);
        stats.setMinMs(2);
        stats.setAvgMs(75.3);
        stats.setCount(8000);
        stats.setWindowStart(start);
        stats.setWindowEnd(end);

        assertThat(stats.getP50Ms()).isEqualTo(15);
        assertThat(stats.getP95Ms()).isEqualTo(50);
        assertThat(stats.getP99Ms()).isEqualTo(120);
        assertThat(stats.getMaxMs()).isEqualTo(500);
        assertThat(stats.getMinMs()).isEqualTo(2);
        assertThat(stats.getAvgMs()).isEqualTo(75.3);
        assertThat(stats.getCount()).isEqualTo(8000);
        assertThat(stats.getWindowStart()).isEqualTo(start);
        assertThat(stats.getWindowEnd()).isEqualTo(end);
    }
}
