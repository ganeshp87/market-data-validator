package com.marketdata.validator.controller;

import com.marketdata.validator.model.Session;
import com.marketdata.validator.model.Tick;
import com.marketdata.validator.store.SessionStore;
import com.marketdata.validator.store.TickStore;
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
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompareControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SessionStore sessionStore;

    @MockBean
    private TickStore tickStore;

    // ── Validation ──────────────────────────────────────

    @Test
    void returnsErrorWhenSessionIdsMissing() throws Exception {
        mvc.perform(post("/api/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("required")));
    }

    @Test
    void returnsErrorWhenComparingSameSession() throws Exception {
        mvc.perform(post("/api/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionIdA\":1,\"sessionIdB\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("itself")));
    }

    @Test
    void returnsErrorWhenSessionANotFound() throws Exception {
        when(sessionStore.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(post("/api/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionIdA\":999,\"sessionIdB\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Session A not found")));
    }

    @Test
    void returnsErrorWhenSessionBNotFound() throws Exception {
        when(sessionStore.findById(1L)).thenReturn(Optional.of(makeSession(1L)));
        when(sessionStore.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(post("/api/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionIdA\":1,\"sessionIdB\":999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Session B not found")));
    }

    // ── Successful comparison ───────────────────────────

    @Test
    void compareReturnsPriceDifferences() throws Exception {
        setupTwoSessions();

        mvc.perform(post("/api/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionIdA\":1,\"sessionIdB\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceDifferences", hasSize(1)))
                .andExpect(jsonPath("$.priceDifferences[0].symbol", is("BTCUSDT")));
    }

    @Test
    void compareReturnsVolumeDifferences() throws Exception {
        setupTwoSessions();

        mvc.perform(post("/api/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionIdA\":1,\"sessionIdB\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.volumeDifferences", hasSize(1)))
                .andExpect(jsonPath("$.volumeDifferences[0].symbol", is("BTCUSDT")));
    }

    @Test
    void compareReturnsSequenceGaps() throws Exception {
        setupTwoSessions();

        mvc.perform(post("/api/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionIdA\":1,\"sessionIdB\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequenceGaps.totalGapsA", is(0)))
                .andExpect(jsonPath("$.sequenceGaps.totalGapsB", is(1)));
    }

    @Test
    void compareReturnsLatencyPatterns() throws Exception {
        setupTwoSessions();

        mvc.perform(post("/api/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionIdA\":1,\"sessionIdB\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latencyPatterns.sessionA.count", is(2)))
                .andExpect(jsonPath("$.latencyPatterns.sessionB.count", is(2)));
    }

    @Test
    void compareReturnsMissingSymbols() throws Exception {
        when(sessionStore.findById(1L)).thenReturn(Optional.of(makeSession(1L)));
        when(sessionStore.findById(2L)).thenReturn(Optional.of(makeSession(2L)));

        // Session A has BTCUSDT and ETHUSDT; Session B only has BTCUSDT
        when(tickStore.findBySessionId(1L)).thenReturn(List.of(
                makeTick("BTCUSDT", "45000", "1", 1, 50),
                makeTick("ETHUSDT", "3000", "10", 1, 50)
        ));
        when(tickStore.findBySessionId(2L)).thenReturn(List.of(
                makeTick("BTCUSDT", "45100", "1", 1, 50)
        ));

        mvc.perform(post("/api/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionIdA\":1,\"sessionIdB\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missingSymbols.onlyInA", hasItem("ETHUSDT")))
                .andExpect(jsonPath("$.missingSymbols.onlyInB", hasSize(0)));
    }

    @Test
    void compareReturnsSessionSummaries() throws Exception {
        setupTwoSessions();

        mvc.perform(post("/api/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionIdA\":1,\"sessionIdB\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionA.id", is(1)))
                .andExpect(jsonPath("$.sessionB.id", is(2)))
                .andExpect(jsonPath("$.sessionA.tickCount", is(2)))
                .andExpect(jsonPath("$.sessionB.tickCount", is(2)));
    }

    @Test
    void compareWithEmptySessionsReturnsEmptyDiffs() throws Exception {
        when(sessionStore.findById(1L)).thenReturn(Optional.of(makeSession(1L)));
        when(sessionStore.findById(2L)).thenReturn(Optional.of(makeSession(2L)));
        when(tickStore.findBySessionId(1L)).thenReturn(List.of());
        when(tickStore.findBySessionId(2L)).thenReturn(List.of());

        mvc.perform(post("/api/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionIdA\":1,\"sessionIdB\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceDifferences", hasSize(0)))
                .andExpect(jsonPath("$.sequenceGaps.totalGapsA", is(0)));
    }

    // ── Helpers ─────────────────────────────────────────

    private void setupTwoSessions() {
        when(sessionStore.findById(1L)).thenReturn(Optional.of(makeSession(1L)));
        when(sessionStore.findById(2L)).thenReturn(Optional.of(makeSession(2L)));

        // Session A: two BTC ticks, sequential
        when(tickStore.findBySessionId(1L)).thenReturn(List.of(
                makeTick("BTCUSDT", "45000", "1.5", 1, 50),
                makeTick("BTCUSDT", "45100", "2.0", 2, 60)
        ));

        // Session B: two BTC ticks, with a sequence gap (1 → 3) and different prices
        when(tickStore.findBySessionId(2L)).thenReturn(List.of(
                makeTick("BTCUSDT", "45200", "1.0", 1, 80),
                makeTick("BTCUSDT", "45400", "3.0", 3, 100)  // gap: 1→3
        ));
    }

    private Session makeSession(long id) {
        Session s = new Session();
        s.setId(id);
        s.setName("Session " + id);
        s.setFeedId("feed-1");
        s.setStatus(Session.Status.COMPLETED);
        s.setStartedAt(Instant.parse("2026-03-25T10:00:00Z"));
        s.setEndedAt(Instant.parse("2026-03-25T10:30:00Z"));
        s.setTickCount(100);
        s.setByteSize(5000);
        return s;
    }

    private Tick makeTick(String symbol, String price, String volume, long seqNum, long latencyMs) {
        Instant exchangeTs = Instant.parse("2026-03-25T10:00:00Z").plusMillis(seqNum * 1000);
        Tick tick = new Tick(symbol, new BigDecimal(price), new BigDecimal(volume),
                seqNum, exchangeTs, "feed-1");
        tick.setReceivedTimestamp(exchangeTs.plusMillis(latencyMs));
        return tick;
    }
}
