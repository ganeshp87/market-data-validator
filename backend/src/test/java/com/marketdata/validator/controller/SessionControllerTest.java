package com.marketdata.validator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketdata.validator.model.Session;
import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.session.SessionExporter;
import com.marketdata.validator.session.SessionRecorder;
import com.marketdata.validator.store.SessionStore;
import com.marketdata.validator.store.TickStore;
import com.marketdata.validator.validator.ValidatorEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SessionRecorder recorder;

    @MockBean
    private SessionStore sessionStore;

    @MockBean
    private TickStore tickStore;

    @MockBean
    private ValidatorEngine engine;

    @MockBean
    private SessionExporter exporter;

    @Autowired
    private ObjectMapper objectMapper;

    // --- GET /api/sessions ---

    @Test
    void listSessionsReturnsAll() throws Exception {
        Session s = completedSession(1L, "Test");
        when(sessionStore.findAll()).thenReturn(List.of(s));

        mvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Test")));
    }

    @Test
    void listSessionsReturnsEmptyWhenNone() throws Exception {
        when(sessionStore.findAll()).thenReturn(List.of());

        mvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // --- POST /api/sessions/start ---

    @Test
    void startRecordingReturns201() throws Exception {
        Session session = recordingSession(1L, "BTC Morning");
        when(recorder.isRecording()).thenReturn(false);
        when(recorder.start("BTC Morning", "feed-1")).thenReturn(session);

        mvc.perform(post("/api/sessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"BTC Morning\",\"feedId\":\"feed-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("BTC Morning")))
                .andExpect(jsonPath("$.status", is("RECORDING")));
    }

    @Test
    void startRecordingReturns400WhenAlreadyRecording() throws Exception {
        Session active = recordingSession(1L, "Active");
        when(recorder.isRecording()).thenReturn(true);
        when(recorder.getCurrentSession()).thenReturn(active);

        mvc.perform(post("/api/sessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New\",\"feedId\":\"feed-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Already recording")));
    }

    @Test
    void startRecordingReturns400WhenNameMissing() throws Exception {
        mvc.perform(post("/api/sessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedId\":\"feed-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("name")));
    }

    @Test
    void startRecordingReturns400WhenFeedIdMissing() throws Exception {
        mvc.perform(post("/api/sessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("feedId")));
    }

    // --- POST /api/sessions/{id}/stop ---

    @Test
    void stopRecordingReturns200() throws Exception {
        Session active = recordingSession(1L, "Active");
        Session completed = completedSession(1L, "Active");
        when(recorder.isRecording()).thenReturn(true);
        when(recorder.getCurrentSession()).thenReturn(active);
        when(recorder.stop()).thenReturn(completed);

        mvc.perform(post("/api/sessions/1/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")));
    }

    @Test
    void stopRecordingReturns400WhenNotRecording() throws Exception {
        when(recorder.isRecording()).thenReturn(false);

        mvc.perform(post("/api/sessions/1/stop"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("No active")));
    }

    @Test
    void stopRecordingReturns400WhenWrongSessionId() throws Exception {
        Session active = recordingSession(2L, "Other");
        when(recorder.isRecording()).thenReturn(true);
        when(recorder.getCurrentSession()).thenReturn(active);

        mvc.perform(post("/api/sessions/1/stop"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("not the active")));
    }

    // --- DELETE /api/sessions/{id} ---

    @Test
    void deleteSessionReturns204() throws Exception {
        when(sessionStore.findById(1L)).thenReturn(Optional.of(completedSession(1L, "Test")));
        when(recorder.isRecording()).thenReturn(false);

        mvc.perform(delete("/api/sessions/1"))
                .andExpect(status().isNoContent());

        verify(tickStore).deleteBySessionId(1L);
        verify(sessionStore).delete(1L);
    }

    @Test
    void deleteSessionReturns404WhenNotFound() throws Exception {
        when(sessionStore.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(delete("/api/sessions/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteSessionReturns400WhenActivelyRecording() throws Exception {
        Session active = recordingSession(1L, "Active");
        when(sessionStore.findById(1L)).thenReturn(Optional.of(active));
        when(recorder.isRecording()).thenReturn(true);
        when(recorder.getCurrentSession()).thenReturn(active);

        mvc.perform(delete("/api/sessions/1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Stop recording")));
    }

    // --- GET /api/sessions/{id}/ticks ---

    @Test
    void getSessionTicksReturnsTicks() throws Exception {
        when(sessionStore.findById(1L)).thenReturn(Optional.of(completedSession(1L, "Test")));
        Tick tick = new Tick("BTCUSDT", new BigDecimal("45000"), new BigDecimal("1"),
                1L, Instant.now(), "feed-1");
        when(tickStore.findBySessionId(1L)).thenReturn(List.of(tick));

        mvc.perform(get("/api/sessions/1/ticks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].symbol", is("BTCUSDT")));
    }

    @Test
    void getSessionTicksReturns404WhenSessionNotFound() throws Exception {
        when(sessionStore.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/sessions/999/ticks"))
                .andExpect(status().isNotFound());
    }

    // --- GET /api/sessions/{id}/export ---

    @Test
    void exportJsonReturnsSessionWithTicks() throws Exception {
        Session session = completedSession(1L, "Export Test");
        when(sessionStore.findById(1L)).thenReturn(Optional.of(session));
        Tick tick = new Tick("BTCUSDT", new BigDecimal("45000"), new BigDecimal("1"),
                1L, Instant.now(), "feed-1");
        when(tickStore.findBySessionId(1L)).thenReturn(List.of(tick));
        when(exporter.exportAsJson(any(), any())).thenReturn(Map.of(
                "sessionId", 1, "name", "Export Test", "ticks", List.of(tick)));

        mvc.perform(get("/api/sessions/1/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId", is(1)))
                .andExpect(jsonPath("$.name", is("Export Test")))
                .andExpect(jsonPath("$.ticks", hasSize(1)));
    }

    @Test
    void exportJsonDefaultsToJson() throws Exception {
        when(sessionStore.findById(1L)).thenReturn(Optional.of(completedSession(1L, "Test")));
        when(tickStore.findBySessionId(1L)).thenReturn(List.of());
        when(exporter.exportAsJson(any(), any())).thenReturn(Map.of("sessionId", 1));

        mvc.perform(get("/api/sessions/1/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").exists());
    }

    @Test
    void exportCsvReturnsTextCsv() throws Exception {
        Session session = completedSession(1L, "CSV Test");
        when(sessionStore.findById(1L)).thenReturn(Optional.of(session));
        Tick tick = new Tick("BTCUSDT", new BigDecimal("45000"), new BigDecimal("1"),
                1L, Instant.now(), "feed-1");
        when(tickStore.findBySessionId(1L)).thenReturn(List.of(tick));
        when(exporter.exportAsCsv(any())).thenReturn(
                "symbol,price,bid,ask,volume,sequenceNum,exchangeTimestamp,receivedTimestamp,feedId,correlationId\n"
                + "BTCUSDT,45000,,,,1,2026-03-25T00:00:00Z,2026-03-25T00:00:00Z,feed-1,\n");

        mvc.perform(get("/api/sessions/1/export?format=csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv"))
                .andExpect(content().string(containsString("symbol,price")))
                .andExpect(content().string(containsString("BTCUSDT")));
    }

    @Test
    void exportReturns404WhenSessionNotFound() throws Exception {
        when(sessionStore.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/sessions/999/export"))
                .andExpect(status().isNotFound());
    }

    // --- POST /api/sessions/{id}/replay ---

    @Test
    void replayFeedsTicksToEngineAndReturnsResults() throws Exception {
        when(sessionStore.findById(1L)).thenReturn(Optional.of(completedSession(1L, "Test")));
        Tick tick = new Tick("BTCUSDT", new BigDecimal("45000"), new BigDecimal("1"),
                1L, Instant.now(), "feed-1");
        when(tickStore.findBySessionId(1L)).thenReturn(List.of(tick));
        when(engine.getResults()).thenReturn(List.of(
                ValidationResult.pass(ValidationResult.Area.ACCURACY, "OK", 0, 0)));

        mvc.perform(post("/api/sessions/1/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId", is(1)))
                .andExpect(jsonPath("$.ticksReplayed", is(1)))
                .andExpect(jsonPath("$.results", hasSize(1)));

        verify(engine).reset();
        verify(engine).onTick(any(Tick.class));
    }

    @Test
    void replayReturns404WhenSessionNotFound() throws Exception {
        when(sessionStore.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(post("/api/sessions/999/replay"))
                .andExpect(status().isNotFound());
    }

    @Test
    void replayResetsEngineBeforeReplay() throws Exception {
        when(sessionStore.findById(1L)).thenReturn(Optional.of(completedSession(1L, "Test")));
        when(tickStore.findBySessionId(1L)).thenReturn(List.of());
        when(engine.getResults()).thenReturn(List.of());

        mvc.perform(post("/api/sessions/1/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticksReplayed", is(0)));

        verify(engine).reset();
    }

    // --- Helpers ---

    private Session recordingSession(long id, String name) {
        Session s = new Session();
        s.setId(id);
        s.setName(name);
        s.setFeedId("feed-1");
        s.setStatus(Session.Status.RECORDING);
        s.setStartedAt(Instant.now());
        return s;
    }

    private Session completedSession(long id, String name) {
        Session s = new Session();
        s.setId(id);
        s.setName(name);
        s.setFeedId("feed-1");
        s.setStatus(Session.Status.COMPLETED);
        s.setStartedAt(Instant.parse("2026-03-23T10:00:00Z"));
        s.setEndedAt(Instant.parse("2026-03-23T10:30:00Z"));
        s.setTickCount(500);
        s.setByteSize(25000);
        return s;
    }
}
