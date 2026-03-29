package com.marketdata.validator.controller;

import com.marketdata.validator.model.Session;
import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.session.SessionExporter;
import com.marketdata.validator.session.SessionRecorder;
import com.marketdata.validator.store.SessionStore;
import com.marketdata.validator.store.TickStore;
import com.marketdata.validator.validator.ValidatorEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for recording sessions, replay, and export.
 *
 * Endpoints:
 *   GET    /api/sessions                     — list all sessions
 *   POST   /api/sessions/start               — start recording
 *   POST   /api/sessions/{id}/stop           — stop recording
 *   DELETE /api/sessions/{id}                — delete session + its ticks
 *   GET    /api/sessions/{id}/ticks          — get ticks from session
 *   GET    /api/sessions/{id}/export?format= — export session as JSON or CSV
 *   POST   /api/sessions/{id}/replay         — replay ticks through validators
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionRecorder recorder;
    private final SessionStore sessionStore;
    private final TickStore tickStore;
    private final ValidatorEngine engine;
    private final SessionExporter exporter;

    public SessionController(SessionRecorder recorder,
                             SessionStore sessionStore,
                             TickStore tickStore,
                             ValidatorEngine engine,
                             SessionExporter exporter) {
        this.recorder = recorder;
        this.sessionStore = sessionStore;
        this.tickStore = tickStore;
        this.engine = engine;
        this.exporter = exporter;
    }

    /**
     * List all recorded sessions, newest first.
     */
    @GetMapping
    public List<Session> listSessions() {
        return sessionStore.findAll();
    }

    /**
     * Start a new recording session.
     * Body: { "name": "btc-morning-session", "feedId": "feed-uuid" }
     */
    @PostMapping("/start")
    public ResponseEntity<?> startRecording(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String feedId = body.get("feedId");

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "'name' is required"));
        }
        if (feedId == null || feedId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "'feedId' is required"));
        }

        if (recorder.isRecording()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Already recording session: "
                            + recorder.getCurrentSession().getId()));
        }

        Session session = recorder.start(name, feedId);
        return ResponseEntity.status(201).body(session);
    }

    /**
     * Stop the current recording session.
     */
    @PostMapping("/{id}/stop")
    public ResponseEntity<?> stopRecording(@PathVariable long id) {
        if (!recorder.isRecording()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No active recording session"));
        }

        Session current = recorder.getCurrentSession();
        if (current.getId() != id) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error",
                            "Session " + id + " is not the active recording. Active: " + current.getId()));
        }

        Session completed = recorder.stop();
        return ResponseEntity.ok(completed);
    }

    /**
     * Delete a session and all its ticks.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteSession(@PathVariable long id) {
        Optional<Session> session = sessionStore.findById(id);
        if (session.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Don't delete a session that's actively recording
        if (recorder.isRecording() && recorder.getCurrentSession().getId() == id) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Cannot delete session while recording. Stop recording first."));
        }

        tickStore.deleteBySessionId(id);
        sessionStore.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get all ticks from a recorded session.
     */
    @GetMapping("/{id}/ticks")
    public ResponseEntity<?> getSessionTicks(@PathVariable long id) {
        Optional<Session> session = sessionStore.findById(id);
        if (session.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(tickStore.findBySessionId(id));
    }

    /**
     * Export a session as JSON or CSV.
     * Query param: format=json (default) or format=csv
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<?> exportSession(@PathVariable long id,
                                           @RequestParam(defaultValue = "json") String format) {
        Optional<Session> sessionOpt = sessionStore.findById(id);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Session session = sessionOpt.get();
        List<Tick> ticks = tickStore.findBySessionId(id);

        if ("csv".equalsIgnoreCase(format)) {
            String csv = exporter.exportAsCsv(ticks);
            return ResponseEntity.ok()
                    .header("Content-Type", "text/csv")
                    .header("Content-Disposition",
                            "attachment; filename=\"session-" + id + ".csv\"")
                    .body(csv);
        }

        // Default: JSON export
        return ResponseEntity.ok(exporter.exportAsJson(session, ticks));
    }

    /**
     * Replay a recorded session through the validator engine.
     * Feeds all ticks back into ValidatorEngine sequentially.
     */
    @PostMapping("/{id}/replay")
    public ResponseEntity<?> replaySession(@PathVariable long id) {
        Optional<Session> sessionOpt = sessionStore.findById(id);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        engine.reset();
        List<Tick> ticks = tickStore.findBySessionId(id);

        for (Tick tick : ticks) {
            engine.onTick(tick);
        }

        List<ValidationResult> results = engine.getResults();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", id);
        response.put("ticksReplayed", ticks.size());
        response.put("results", results);

        return ResponseEntity.ok(response);
    }
}
