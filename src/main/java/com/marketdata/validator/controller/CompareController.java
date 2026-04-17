package com.marketdata.validator.controller;

import com.marketdata.validator.model.Session;
import com.marketdata.validator.model.Tick;
import com.marketdata.validator.store.SessionStore;
import com.marketdata.validator.store.TickStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Compare two recorded sessions to detect deployment regressions.
 *
 * Blueprint Section 14 — Compare Mode:
 *   POST /api/compare { sessionIdA, sessionIdB }
 *   Returns: price differences, volume differences, sequence gaps,
 *            latency patterns, missing symbols
 *
 * Use cases:
 *   - Before vs After deployment (regression detection)
 *   - Exchange A vs Exchange B (consistency check)
 *   - Replay vs Live (baseline validation)
 */
@RestController
@RequestMapping("/api/compare")
public class CompareController {

    private final SessionStore sessionStore;
    private final TickStore tickStore;

    public CompareController(SessionStore sessionStore, TickStore tickStore) {
        this.sessionStore = sessionStore;
        this.tickStore = tickStore;
    }

    /**
     * Compare two sessions.
     * Body: { "sessionIdA": 1, "sessionIdB": 2 }
     */
    @PostMapping
    public ResponseEntity<?> compareSessions(@RequestBody Map<String, Long> body) {
        Long idA = body.get("sessionIdA");
        Long idB = body.get("sessionIdB");

        if (idA == null || idB == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Both 'sessionIdA' and 'sessionIdB' are required"));
        }
        if (idA.equals(idB)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Cannot compare a session with itself"));
        }

        Optional<Session> sessionA = sessionStore.findById(idA);
        Optional<Session> sessionB = sessionStore.findById(idB);

        if (sessionA.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Session A not found: " + idA));
        }
        if (sessionB.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Session B not found: " + idB));
        }

        List<Tick> ticksA = tickStore.findBySessionId(idA);
        List<Tick> ticksB = tickStore.findBySessionId(idB);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionA", sessionSummary(sessionA.get(), ticksA.size()));
        result.put("sessionB", sessionSummary(sessionB.get(), ticksB.size()));
        result.put("priceDifferences", comparePrices(ticksA, ticksB));
        result.put("volumeDifferences", compareVolumes(ticksA, ticksB));
        result.put("sequenceGaps", compareSequenceGaps(ticksA, ticksB));
        result.put("latencyPatterns", compareLatency(ticksA, ticksB));
        result.put("missingSymbols", compareMissingSymbols(ticksA, ticksB));

        return ResponseEntity.ok(result);
    }

    // ── Helpers ─────────────────────────────────────────

    private Map<String, Object> sessionSummary(Session s, int tickCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("tickCount", tickCount);
        m.put("startedAt", s.getStartedAt());
        m.put("endedAt", s.getEndedAt());
        return m;
    }

    /**
     * Compare average prices per symbol between sessions.
     * Returns list of { symbol, avgPriceA, avgPriceB, diffPercent }
     */
    private List<Map<String, Object>> comparePrices(List<Tick> ticksA, List<Tick> ticksB) {
        Map<String, BigDecimal> avgA = averagePriceBySymbol(ticksA);
        Map<String, BigDecimal> avgB = averagePriceBySymbol(ticksB);

        Set<String> allSymbols = new TreeSet<>();
        allSymbols.addAll(avgA.keySet());
        allSymbols.addAll(avgB.keySet());

        List<Map<String, Object>> diffs = new ArrayList<>();
        for (String symbol : allSymbols) {
            BigDecimal priceA = avgA.get(symbol);
            BigDecimal priceB = avgB.get(symbol);
            if (priceA == null || priceB == null) continue;

            BigDecimal diff = priceB.subtract(priceA);
            double diffPercent = priceA.compareTo(BigDecimal.ZERO) != 0
                    ? diff.divide(priceA, MathContext.DECIMAL64).doubleValue() * 100.0
                    : 0.0;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("symbol", symbol);
            entry.put("avgPriceA", priceA.toPlainString());
            entry.put("avgPriceB", priceB.toPlainString());
            entry.put("diffPercent", Math.round(diffPercent * 100.0) / 100.0);
            diffs.add(entry);
        }
        return diffs;
    }

    /**
     * Compare average volumes per symbol.
     */
    private List<Map<String, Object>> compareVolumes(List<Tick> ticksA, List<Tick> ticksB) {
        Map<String, BigDecimal> avgA = averageVolumeBySymbol(ticksA);
        Map<String, BigDecimal> avgB = averageVolumeBySymbol(ticksB);

        Set<String> allSymbols = new TreeSet<>();
        allSymbols.addAll(avgA.keySet());
        allSymbols.addAll(avgB.keySet());

        List<Map<String, Object>> diffs = new ArrayList<>();
        for (String symbol : allSymbols) {
            BigDecimal volA = avgA.get(symbol);
            BigDecimal volB = avgB.get(symbol);
            if (volA == null || volB == null) continue;

            BigDecimal diff = volB.subtract(volA);
            double diffPercent = volA.compareTo(BigDecimal.ZERO) != 0
                    ? diff.divide(volA, MathContext.DECIMAL64).doubleValue() * 100.0
                    : 0.0;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("symbol", symbol);
            entry.put("avgVolumeA", volA.toPlainString());
            entry.put("avgVolumeB", volB.toPlainString());
            entry.put("diffPercent", Math.round(diffPercent * 100.0) / 100.0);
            diffs.add(entry);
        }
        return diffs;
    }

    /**
     * Compare sequence gaps per symbol.
     * A gap is a jump > 1 in sequential sequence numbers for a symbol.
     */
    private Map<String, Object> compareSequenceGaps(List<Tick> ticksA, List<Tick> ticksB) {
        Map<String, Integer> gapsA = countSequenceGaps(ticksA);
        Map<String, Integer> gapsB = countSequenceGaps(ticksB);

        int totalGapsA = gapsA.values().stream().mapToInt(Integer::intValue).sum();
        int totalGapsB = gapsB.values().stream().mapToInt(Integer::intValue).sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalGapsA", totalGapsA);
        result.put("totalGapsB", totalGapsB);
        result.put("newGapsInB", Math.max(0, totalGapsB - totalGapsA));
        result.put("perSymbolA", gapsA);
        result.put("perSymbolB", gapsB);
        return result;
    }

    /**
     * Compare latency stats (p50, p95, max) per session.
     */
    private Map<String, Object> compareLatency(List<Tick> ticksA, List<Tick> ticksB) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionA", latencyStats(ticksA));
        result.put("sessionB", latencyStats(ticksB));
        return result;
    }

    /**
     * Find symbols present in one session but not the other.
     */
    private Map<String, Object> compareMissingSymbols(List<Tick> ticksA, List<Tick> ticksB) {
        Set<String> symbolsA = ticksA.stream().map(Tick::getSymbol).collect(Collectors.toSet());
        Set<String> symbolsB = ticksB.stream().map(Tick::getSymbol).collect(Collectors.toSet());

        Set<String> onlyInA = new TreeSet<>(symbolsA);
        onlyInA.removeAll(symbolsB);

        Set<String> onlyInB = new TreeSet<>(symbolsB);
        onlyInB.removeAll(symbolsA);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("onlyInA", onlyInA);
        result.put("onlyInB", onlyInB);
        return result;
    }

    // ── Statistical helpers ─────────────────────────────

    private Map<String, BigDecimal> averagePriceBySymbol(List<Tick> ticks) {
        return ticks.stream()
                .filter(t -> t.getPrice() != null)
                .collect(Collectors.groupingBy(Tick::getSymbol))
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> {
                            List<Tick> list = e.getValue();
                            BigDecimal sum = list.stream()
                                    .map(Tick::getPrice)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                            return sum.divide(BigDecimal.valueOf(list.size()), MathContext.DECIMAL64);
                        }
                ));
    }

    private Map<String, BigDecimal> averageVolumeBySymbol(List<Tick> ticks) {
        return ticks.stream()
                .filter(t -> t.getVolume() != null)
                .collect(Collectors.groupingBy(Tick::getSymbol))
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> {
                            List<Tick> list = e.getValue();
                            BigDecimal sum = list.stream()
                                    .map(Tick::getVolume)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                            return sum.divide(BigDecimal.valueOf(list.size()), MathContext.DECIMAL64);
                        }
                ));
    }

    private Map<String, Integer> countSequenceGaps(List<Tick> ticks) {
        Map<String, Integer> gapCounts = new TreeMap<>();
        Map<String, Long> lastSeq = new HashMap<>();

        for (Tick tick : ticks) {
            String symbol = tick.getSymbol();
            Long prev = lastSeq.get(symbol);
            if (prev != null && tick.getSequenceNum() > prev + 1) {
                gapCounts.merge(symbol, 1, Integer::sum);
            }
            lastSeq.put(symbol, tick.getSequenceNum());
        }
        return gapCounts;
    }

    private Map<String, Object> latencyStats(List<Tick> ticks) {
        long[] latencies = ticks.stream()
                .mapToLong(Tick::getLatencyMs)
                .sorted()
                .toArray();

        Map<String, Object> stats = new LinkedHashMap<>();
        if (latencies.length == 0) {
            stats.put("count", 0);
            stats.put("p50", 0);
            stats.put("p95", 0);
            stats.put("max", 0);
            return stats;
        }

        stats.put("count", latencies.length);
        stats.put("p50", percentile(latencies, 50));
        stats.put("p95", percentile(latencies, 95));
        stats.put("max", latencies[latencies.length - 1]);
        return stats;
    }

    private long percentile(long[] sorted, int p) {
        if (sorted.length == 0) return 0;
        int index = (int) Math.ceil((p / 100.0) * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
}
