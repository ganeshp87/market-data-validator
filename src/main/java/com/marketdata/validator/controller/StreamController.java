package com.marketdata.validator.controller;

import com.marketdata.validator.feed.FeedManager;
import com.marketdata.validator.model.Alert;
import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.store.AlertStore;
import com.marketdata.validator.validator.ValidatorEngine;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * SSE (Server-Sent Events) controller for live streaming to the browser.
 *
 * Blueprint Section 8 — Live Streaming Endpoints:
 *   GET /api/stream/ticks          — live tick stream (optional ?symbol= filter)
 *   GET /api/stream/validation     — live validation results (all 9 areas)
 *   GET /api/stream/latency        — latency stats every 1 second
 *   GET /api/stream/throughput     — messages/sec every 1 second
 *
 * Implementation:
 *   - Uses Spring MVC's SseEmitter (not WebFlux Flux)
 *   - Registers as FeedManager global tick listener and ValidatorEngine listener
 *   - Manages a set of active emitters per endpoint
 *   - Scheduled tasks push latency and throughput stats every 1 second
 */
@RestController
@RequestMapping("/api/stream")
public class StreamController {

    private static final Logger log = LoggerFactory.getLogger(StreamController.class);
    private static final long SSE_TIMEOUT = 0L; // No timeout — connection stays open

    private final FeedManager feedManager;
    private final ValidatorEngine engine;
    private final AlertStore alertStore;

    // Active SSE emitters per endpoint
    private final List<SseEmitter> tickEmitters = new CopyOnWriteArrayList<>();
    private final List<SseEmitter> validationEmitters = new CopyOnWriteArrayList<>();
    private final List<SseEmitter> latencyEmitters = new CopyOnWriteArrayList<>();
    private final List<SseEmitter> throughputEmitters = new CopyOnWriteArrayList<>();
    private final List<SseEmitter> alertEmitters = new CopyOnWriteArrayList<>();

    // Throughput tracking
    private final AtomicLong ticksInWindow = new AtomicLong(0);
    private final AtomicLong totalTicksEver = new AtomicLong(0);
    private volatile long peakPerSecond = 0;

    // Scheduled executor for periodic stats
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-stats-scheduler");
                t.setDaemon(true);
                return t;
            });

    public StreamController(FeedManager feedManager, ValidatorEngine engine, AlertStore alertStore) {
        this.feedManager = feedManager;
        this.engine = engine;
        this.alertStore = alertStore;

        // Register as tick listener on FeedManager
        feedManager.addGlobalTickListener(this::onTick);

        // Register as validation result listener on ValidatorEngine
        engine.addListener(this::onValidationUpdate);

        // Register as alert listener to broadcast new alerts via SSE
        alertStore.addListener(this::onNewAlert);

        // Schedule periodic stats broadcasts (latency + throughput) every 1 second
        scheduler.scheduleAtFixedRate(this::broadcastLatencyStats, 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::broadcastThroughputStats, 1, 1, TimeUnit.SECONDS);
    }

    // --- SSE Endpoints ---

    /**
     * GET /api/stream/ticks — live tick stream.
     * Optional query params:
     *   ?symbol=BTCUSDT  — filter by symbol (case-insensitive)
     *   ?feedId=abc123   — filter to one feed connection (prevents LVWR numeric symbols
     *                      appearing in the Binance view when both feeds are running)
     */
    @GetMapping("/ticks")
    public SseEmitter streamTicks(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String feedId) {
        SseEmitter emitter = createEmitter(tickEmitters);

        if (symbol != null && !symbol.isBlank()) {
            symbolFilters.put(emitter, symbol.toUpperCase());
        }
        if (feedId != null && !feedId.isBlank()) {
            feedIdFilters.put(emitter, feedId);
        }

        return emitter;
    }

    // Per-emitter filter maps
    private final ConcurrentHashMap<SseEmitter, String> symbolFilters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SseEmitter, String> feedIdFilters  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SseEmitter, String> validationFeedFilters = new ConcurrentHashMap<>();

    /**
     * GET /api/stream/validation — live validation results (all 9 areas).
     * Optional ?feedId= scopes results to a single feed (Fix 8).
     */
    @GetMapping("/validation")
    public SseEmitter streamValidation(
            @RequestParam(required = false) String feedId) {
        SseEmitter emitter = createEmitter(validationEmitters);
        if (feedId != null && !feedId.isBlank()) {
            validationFeedFilters.put(emitter, feedId);
        }
        return emitter;
    }

    /**
     * GET /api/stream/latency — latency stats every 1 second.
     */
    @GetMapping("/latency")
    public SseEmitter streamLatency() {
        return createEmitter(latencyEmitters);
    }

    /**
     * GET /api/stream/throughput — messages/sec every 1 second.
     */
    @GetMapping("/throughput")
    public SseEmitter streamThroughput() {
        return createEmitter(throughputEmitters);
    }

    /**
     * GET /api/stream/alerts — live alert stream.
     */
    @GetMapping("/alerts")
    public SseEmitter streamAlerts() {
        return createEmitter(alertEmitters);
    }

    /**
     * GET /api/stream/stats — aggregated snapshot suitable for a dashboard header.
     * Returns: totalTicks, messagesPerSecond (last second), peakPerSecond,
     *          activeConnections, overallStatus, activeEmitters.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        List<ValidationResult> results = engine.getResults();
        long active = feedManager.getActiveConnectionCount();
        Map<String, Object> stats = Map.of(
                "timestamp", Instant.now(),
                "totalTicks", totalTicksEver.get(),
                "messagesPerSecond", ticksInWindow.get(),
                "peakPerSecond", peakPerSecond,
                "activeConnections", active,
                "overallStatus", computeOverallStatus(results),
                "activeEmitters", tickEmitters.size() + validationEmitters.size()
                        + latencyEmitters.size() + throughputEmitters.size()
        );
        return ResponseEntity.ok(stats);
    }

    // --- Internal: Tick handling ---

    private void onTick(Tick tick) {
        ticksInWindow.incrementAndGet();
        totalTicksEver.incrementAndGet();

        for (SseEmitter emitter : tickEmitters) {
            try {
                // Apply feedId filter — skip if tick is from a different feed
                String feedFilter = feedIdFilters.get(emitter);
                if (feedFilter != null && !feedFilter.equals(tick.getFeedId())) {
                    continue;
                }
                // Apply symbol filter
                String symFilter = symbolFilters.get(emitter);
                if (symFilter != null && !symFilter.equals(tick.getSymbol())) {
                    continue;
                }

                emitter.send(SseEmitter.event()
                        .name("tick")
                        .data(formatTick(tick)));
            } catch (IOException e) {
                removeEmitter(emitter, tickEmitters);
            }
        }
    }

    // --- Internal: Validation handling ---

    private void onValidationUpdate(List<ValidationResult> results) {
        // Global payload (no feed filter)
        Map<String, Object> globalPayload = Map.of(
                "timestamp", Instant.now(),
                "results", engine.getResultsByArea(),
                "overallStatus", computeOverallStatus(results),
                "ticksProcessed", engine.getTickCount(),
                "rejectedCount", engine.getRejectedCount(),
                "duplicateCount", engine.getDuplicateCount()
        );

        for (SseEmitter emitter : validationEmitters) {
            try {
                String feedFilter = validationFeedFilters.get(emitter);
                Map<String, Object> payload;
                if (feedFilter != null) {
                    // Per-feed scoped results (Fix 8)
                    Map<String, ValidationResult> feedResults = engine.getResultsByArea(feedFilter);
                    if (feedResults == null) feedResults = Map.of();
                    List<ValidationResult> feedResultList = List.copyOf(feedResults.values());
                    payload = Map.of(
                            "timestamp", Instant.now(),
                            "results", feedResults,
                            "overallStatus", computeOverallStatus(feedResultList),
                            "ticksProcessed", engine.getTickCount(feedFilter),
                            "rejectedCount", engine.getRejectedCount(),
                            "duplicateCount", engine.getDuplicateCount()
                    );
                } else {
                    payload = globalPayload;
                }
                emitter.send(SseEmitter.event()
                        .name("validation")
                        .data(payload));
            } catch (IOException e) {
                removeEmitter(emitter, validationEmitters);
            }
        }
    }

    // --- Internal: Periodic stats ---

    private void broadcastLatencyStats() {
        if (latencyEmitters.isEmpty()) {
            return;
        }

        // Get latency stats from the LATENCY validator's result details
        Map<String, ValidationResult> resultsByArea = engine.getResultsByArea();
        ValidationResult latencyResult = resultsByArea.get("LATENCY");
        if (latencyResult == null) {
            return;
        }

        Map<String, Object> details = latencyResult.getDetails();
        Map<String, Object> payload = Map.of(
                "timestamp", Instant.now(),
                "p50", details.getOrDefault("p50", 0),
                "p95", details.getOrDefault("p95", 0),
                "p99", details.getOrDefault("p99", 0),
                "min", details.getOrDefault("min", 0),
                "max", details.getOrDefault("max", 0),
                "count", details.getOrDefault("totalTicks", 0)
        );

        for (SseEmitter emitter : latencyEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("latency")
                        .data(payload));
            } catch (IOException e) {
                removeEmitter(emitter, latencyEmitters);
            }
        }
    }

    private void broadcastThroughputStats() {
        if (throughputEmitters.isEmpty()) {
            return;
        }

        long currentRate = ticksInWindow.getAndSet(0);
        if (currentRate > peakPerSecond) {
            peakPerSecond = currentRate;
        }
        long total = totalTicksEver.get();

        Map<String, Object> payload = Map.of(
                "timestamp", Instant.now(),
                "messagesPerSecond", currentRate,
                "peakPerSecond", peakPerSecond,
                "totalMessages", total
        );

        for (SseEmitter emitter : throughputEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("throughput")
                        .data(payload));
            } catch (IOException e) {
                removeEmitter(emitter, throughputEmitters);
            }
        }
    }

    // --- Internal: Emitter management ---

    private SseEmitter createEmitter(List<SseEmitter> emitterList) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        emitterList.add(emitter);

        emitter.onCompletion(() -> removeEmitter(emitter, emitterList));
        emitter.onTimeout(() -> removeEmitter(emitter, emitterList));
        emitter.onError(e -> removeEmitter(emitter, emitterList));

        // Send an initial comment to flush the HTTP response headers immediately.
        // Without this, some proxies (e.g. Vite dev server) buffer the response
        // until the first real event arrives, preventing the browser's EventSource
        // from firing its 'open' event and showing as 'Disconnected'.
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            removeEmitter(emitter, emitterList);
        }

        return emitter;
    }

    private void removeEmitter(SseEmitter emitter, List<SseEmitter> emitterList) {
        emitterList.remove(emitter);
        symbolFilters.remove(emitter);
        feedIdFilters.remove(emitter);
        validationFeedFilters.remove(emitter);
    }

    // --- Internal: Formatting ---

    private Map<String, Object> formatTick(Tick tick) {
        return Map.of(
                "symbol", tick.getSymbol() != null ? tick.getSymbol() : "",
                "price", tick.getPrice() != null ? tick.getPrice().toPlainString() : "0",
                "bid", tick.getBid() != null ? tick.getBid().toPlainString() : "",
                "ask", tick.getAsk() != null ? tick.getAsk().toPlainString() : "",
                "volume", tick.getVolume() != null ? tick.getVolume().toPlainString() : "",
                "sequenceNum", tick.getSequenceNum(),
                "exchangeTimestamp", tick.getExchangeTimestamp() != null ? tick.getExchangeTimestamp().toString() : "",
                "receivedTimestamp", tick.getReceivedTimestamp() != null ? tick.getReceivedTimestamp().toString() : "",
                "feedId", tick.getFeedId() != null ? tick.getFeedId() : "",
                "latency", tick.getLatencyMs()
        );
    }

    private String computeOverallStatus(List<ValidationResult> results) {
        boolean hasFail = false;
        boolean hasWarn = false;
        for (ValidationResult r : results) {
            if (r.getStatus() == ValidationResult.Status.FAIL) hasFail = true;
            if (r.getStatus() == ValidationResult.Status.WARN) hasWarn = true;
        }
        if (hasFail) return "FAIL";
        if (hasWarn) return "WARN";
        return "PASS";
    }

    // --- Internal: Alert handling ---

    private void onNewAlert(Alert alert) {
        Map<String, Object> payload = Map.of(
                "id", alert.getId(),
                "area", alert.getArea(),
                "severity", alert.getSeverity().name(),
                "message", alert.getMessage(),
                "acknowledged", alert.isAcknowledged(),
                "createdAt", alert.getCreatedAt().toString()
        );

        for (SseEmitter emitter : alertEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("alert")
                        .data(payload));
            } catch (IOException e) {
                removeEmitter(emitter, alertEmitters);
            }
        }
    }

    // --- For testing: expose emitter counts ---

    @PreDestroy
    void destroy() {
        log.info("StreamController shutting down — stopping stats scheduler");
        scheduler.shutdownNow();
    }

    int getTickEmitterCount() {
        return tickEmitters.size();
    }

    int getValidationEmitterCount() {
        return validationEmitters.size();
    }

    int getLatencyEmitterCount() {
        return latencyEmitters.size();
    }

    int getThroughputEmitterCount() {
        return throughputEmitters.size();
    }

    int getAlertEmitterCount() {
        return alertEmitters.size();
    }
}
