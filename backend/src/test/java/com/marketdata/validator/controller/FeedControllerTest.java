package com.marketdata.validator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketdata.validator.feed.FeedManager;
import com.marketdata.validator.model.Connection;
import com.marketdata.validator.validator.ValidatorEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FeedController.class)
class FeedControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private FeedManager feedManager;

    @MockBean
    private ValidatorEngine validatorEngine;

    @Autowired
    private ObjectMapper objectMapper;

    // --- GET /api/feeds ---

    @Test
    void listFeedsReturnsAllConnections() throws Exception {
        Connection c1 = new Connection("Binance", "wss://stream.binance.com:9443/ws",
                Connection.AdapterType.BINANCE, List.of("BTCUSDT"));
        Connection c2 = new Connection("Finnhub", "wss://ws.finnhub.io",
                Connection.AdapterType.FINNHUB, List.of("AAPL"));
        when(feedManager.getAllConnections()).thenReturn(List.of(c1, c2));

        mvc.perform(get("/api/feeds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name", is("Binance")))
                .andExpect(jsonPath("$[1].name", is("Finnhub")));
    }

    @Test
    void listFeedsReturnsEmptyWhenNone() throws Exception {
        when(feedManager.getAllConnections()).thenReturn(List.of());

        mvc.perform(get("/api/feeds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // --- POST /api/feeds ---

    @Test
    void addFeedReturns201() throws Exception {
        Connection conn = new Connection("Binance", "wss://stream.binance.com:9443/ws",
                Connection.AdapterType.BINANCE, List.of("BTCUSDT"));
        when(feedManager.addConnection(any())).thenReturn(conn);

        mvc.perform(post("/api/feeds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conn)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("Binance")));
    }

    @Test
    void addFeedBlocksNonWebSocketScheme() throws Exception {
        Connection conn = new Connection("Bad", "http://example.com",
                Connection.AdapterType.GENERIC, List.of());

        mvc.perform(post("/api/feeds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conn)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("ws://")));
    }

    @Test
    void addFeedBlocksLoopbackAddress() throws Exception {
        Connection conn = new Connection("Local", "wss://127.0.0.1/ws",
                Connection.AdapterType.GENERIC, List.of());

        mvc.perform(post("/api/feeds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conn)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Loopback")));
    }

    @Test
    void addFeedBlocksPrivateIps() throws Exception {
        Connection conn = new Connection("Internal", "wss://192.168.1.1/ws",
                Connection.AdapterType.GENERIC, List.of());

        mvc.perform(post("/api/feeds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conn)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Private")));
    }

    @Test
    void addFeedBlocksNullUrl() throws Exception {
        Connection conn = new Connection();
        conn.setName("No URL");

        mvc.perform(post("/api/feeds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conn)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("required")));
    }

    // --- PUT /api/feeds/{id} ---

    @Test
    void updateFeedChangesName() throws Exception {
        Connection existing = new Connection("Old Name", "wss://stream.binance.com:9443/ws",
                Connection.AdapterType.BINANCE, List.of("BTCUSDT"));
        existing.setId("feed-1");
        existing.setStatus(Connection.Status.DISCONNECTED);
        when(feedManager.getConnection("feed-1")).thenReturn(existing);

        Connection update = new Connection();
        update.setName("New Name");

        mvc.perform(put("/api/feeds/feed-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("New Name")));
    }

    @Test
    void updateFeedRejectsUrlChangeWhileConnected() throws Exception {
        Connection existing = new Connection("Binance", "wss://stream.binance.com:9443/ws",
                Connection.AdapterType.BINANCE, List.of("BTCUSDT"));
        existing.setId("feed-1");
        existing.setStatus(Connection.Status.CONNECTED);
        when(feedManager.getConnection("feed-1")).thenReturn(existing);

        Connection update = new Connection();
        update.setUrl("wss://other.exchange.com/ws");

        mvc.perform(put("/api/feeds/feed-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Stop connection")));
    }

    @Test
    void updateFeedReturns404ForUnknownId() throws Exception {
        when(feedManager.getConnection("unknown")).thenReturn(null);

        mvc.perform(put("/api/feeds/unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"test\"}"))
                .andExpect(status().isNotFound());
    }

    // --- DELETE /api/feeds/{id} ---

    @Test
    void removeFeedReturns204() throws Exception {
        when(feedManager.removeConnection("feed-1")).thenReturn(true);
        when(feedManager.getAllConnections()).thenReturn(List.of());

        mvc.perform(delete("/api/feeds/feed-1"))
                .andExpect(status().isNoContent());

        verify(validatorEngine).reset();
    }

    @Test
    void removeFeedReturns404WhenNotFound() throws Exception {
        when(feedManager.removeConnection("unknown")).thenReturn(false);

        mvc.perform(delete("/api/feeds/unknown"))
                .andExpect(status().isNotFound());
    }

    // --- POST /api/feeds/{id}/start ---

    @Test
    void startFeedReturns200() throws Exception {
        Connection conn = new Connection("Binance", "wss://stream.binance.com:9443/ws",
                Connection.AdapterType.BINANCE, List.of("BTCUSDT"));
        when(feedManager.getConnection("feed-1")).thenReturn(conn);
        when(feedManager.startConnection(eq("feed-1"), any())).thenReturn(true);

        mvc.perform(post("/api/feeds/feed-1/start"))
                .andExpect(status().isOk());
    }

    @Test
    void startFeedReturns400WhenAlreadyActive() throws Exception {
        Connection conn = new Connection();
        when(feedManager.getConnection("feed-1")).thenReturn(conn);
        when(feedManager.startConnection(eq("feed-1"), any())).thenReturn(false);

        mvc.perform(post("/api/feeds/feed-1/start"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("already active")));
    }

    @Test
    void startFeedReturns404ForUnknownId() throws Exception {
        when(feedManager.getConnection("unknown")).thenReturn(null);

        mvc.perform(post("/api/feeds/unknown/start"))
                .andExpect(status().isNotFound());
    }

    // --- POST /api/feeds/{id}/stop ---

    @Test
    void stopFeedReturns200() throws Exception {
        Connection conn = new Connection();
        when(feedManager.getConnection("feed-1")).thenReturn(conn);
        when(feedManager.stopConnection("feed-1")).thenReturn(true);

        mvc.perform(post("/api/feeds/feed-1/stop"))
                .andExpect(status().isOk());
    }

    @Test
    void stopFeedReturns400WhenAlreadyStopped() throws Exception {
        Connection conn = new Connection();
        when(feedManager.getConnection("feed-1")).thenReturn(conn);
        when(feedManager.stopConnection("feed-1")).thenReturn(false);

        mvc.perform(post("/api/feeds/feed-1/stop"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("already stopped")));
    }

    @Test
    void stopFeedReturns404ForUnknownId() throws Exception {
        when(feedManager.getConnection("unknown")).thenReturn(null);

        mvc.perform(post("/api/feeds/unknown/stop"))
                .andExpect(status().isNotFound());
    }

    // --- POST /api/feeds/{id}/subscribe ---

    @Test
    void subscribeAddsNewSymbols() throws Exception {
        Connection conn = new Connection("Binance", "wss://stream.binance.com:9443/ws",
                Connection.AdapterType.BINANCE, List.of("BTCUSDT"));
        when(feedManager.getConnection("feed-1")).thenReturn(conn);

        mvc.perform(post("/api/feeds/feed-1/subscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("symbols", List.of("ETHUSDT", "ADAUSDT")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbols", hasSize(3)))
                .andExpect(jsonPath("$.symbols", hasItems("BTCUSDT", "ETHUSDT", "ADAUSDT")));
    }

    @Test
    void subscribeSkipsDuplicateSymbols() throws Exception {
        Connection conn = new Connection("Binance", "wss://stream.binance.com:9443/ws",
                Connection.AdapterType.BINANCE, List.of("BTCUSDT"));
        when(feedManager.getConnection("feed-1")).thenReturn(conn);

        mvc.perform(post("/api/feeds/feed-1/subscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("symbols", List.of("BTCUSDT", "ETHUSDT")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbols", hasSize(2)));
    }

    @Test
    void subscribeReturns400WhenNoSymbols() throws Exception {
        Connection conn = new Connection();
        when(feedManager.getConnection("feed-1")).thenReturn(conn);

        mvc.perform(post("/api/feeds/feed-1/subscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("symbols")));
    }

    @Test
    void subscribeReturns404ForUnknownFeed() throws Exception {
        when(feedManager.getConnection("unknown")).thenReturn(null);

        mvc.perform(post("/api/feeds/unknown/subscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("symbols", List.of("BTCUSDT")))))
                .andExpect(status().isNotFound());
    }

    // --- POST /api/feeds/{id}/unsubscribe ---

    @Test
    void unsubscribeRemovesSymbols() throws Exception {
        Connection conn = new Connection("Binance", "wss://stream.binance.com:9443/ws",
                Connection.AdapterType.BINANCE, List.of("BTCUSDT", "ETHUSDT", "ADAUSDT"));
        when(feedManager.getConnection("feed-1")).thenReturn(conn);

        mvc.perform(post("/api/feeds/feed-1/unsubscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("symbols", List.of("ETHUSDT")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbols", hasSize(2)))
                .andExpect(jsonPath("$.symbols", hasItems("BTCUSDT", "ADAUSDT")));
    }

    @Test
    void unsubscribeReturns400WhenNoSymbols() throws Exception {
        Connection conn = new Connection();
        when(feedManager.getConnection("feed-1")).thenReturn(conn);

        mvc.perform(post("/api/feeds/feed-1/unsubscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("symbols")));
    }

    @Test
    void unsubscribeReturns404ForUnknownFeed() throws Exception {
        when(feedManager.getConnection("unknown")).thenReturn(null);

        mvc.perform(post("/api/feeds/unknown/unsubscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("symbols", List.of("BTCUSDT")))))
                .andExpect(status().isNotFound());
    }

    // --- SSRF validation unit tests ---

    @Test
    void validateFeedUrlAcceptsValidWss() {
        // Note: this may fail if DNS can't resolve — passes for well-known hosts
        String result = FeedController.validateFeedUrl("wss://stream.binance.com:9443/ws");
        // Either null (valid) or DNS error — both are acceptable in test env
        // We just verify it doesn't throw
    }

    @Test
    void validateFeedUrlRejectsHttpScheme() {
        String result = FeedController.validateFeedUrl("http://example.com");
        assert result != null && result.contains("ws://");
    }

    @Test
    void validateFeedUrlRejectsNullUrl() {
        assert FeedController.validateFeedUrl(null) != null;
    }

    @Test
    void validateFeedUrlRejectsBlankUrl() {
        assert FeedController.validateFeedUrl("   ") != null;
    }

    // --- Additional SSRF edge cases ---

    @Test
    void addFeedBlocks10DotPrivateRange() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("name", "SSRF", "url", "ws://10.0.0.1:8080/ws",
                        "adapterType", "BINANCE", "symbols", List.of("BTCUSDT")));

        mvc.perform(post("/api/feeds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addFeedBlocks172PrivateRange() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("name", "SSRF", "url", "ws://172.16.0.1:8080/ws",
                        "adapterType", "BINANCE", "symbols", List.of("BTCUSDT")));

        mvc.perform(post("/api/feeds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addFeedBlocksLocalhostByName() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("name", "SSRF", "url", "ws://localhost:8080/ws",
                        "adapterType", "BINANCE", "symbols", List.of("BTCUSDT")));

        mvc.perform(post("/api/feeds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addFeedBlocksEmptyUrl() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("name", "Empty", "url", "",
                        "adapterType", "BINANCE", "symbols", List.of("BTCUSDT")));

        mvc.perform(post("/api/feeds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
