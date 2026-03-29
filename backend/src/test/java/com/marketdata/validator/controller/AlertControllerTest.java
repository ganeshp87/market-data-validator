package com.marketdata.validator.controller;

import com.marketdata.validator.model.Alert;
import com.marketdata.validator.model.Alert.Severity;
import com.marketdata.validator.store.AlertStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private AlertStore alertStore;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM alerts");
    }

    // --- GET /api/alerts ---

    @Test
    void getAll_empty() throws Exception {
        mvc.perform(get("/api/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void getAll_returnsAlerts() throws Exception {
        Alert a1 = new Alert("LATENCY", Severity.CRITICAL, "p95 high");
        a1.setCreatedAt(java.time.Instant.parse("2024-01-01T10:00:00Z"));
        Alert a2 = new Alert("ACCURACY", Severity.WARN, "drift");
        a2.setCreatedAt(java.time.Instant.parse("2024-01-01T10:00:01Z"));
        alertStore.save(a1);
        alertStore.save(a2);

        mvc.perform(get("/api/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].area", is("ACCURACY")))  // DESC order
                .andExpect(jsonPath("$[1].area", is("LATENCY")));
    }

    // --- GET /api/alerts/unacknowledged ---

    @Test
    void getUnacknowledged_filtersAcknowledged() throws Exception {
        Alert a1 = new Alert("LATENCY", Severity.CRITICAL, "active");
        alertStore.save(a1);
        Alert a2 = new Alert("ACCURACY", Severity.WARN, "acked");
        alertStore.save(a2);
        alertStore.acknowledge(a2.getId());

        mvc.perform(get("/api/alerts/unacknowledged"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].message", is("active")));
    }

    // --- GET /api/alerts/count ---

    @Test
    void getCount_returnsUnacknowledgedCount() throws Exception {
        alertStore.save(new Alert("LATENCY", Severity.CRITICAL, "one"));
        alertStore.save(new Alert("ACCURACY", Severity.WARN, "two"));
        Alert a3 = new Alert("ORDERING", Severity.INFO, "three");
        alertStore.save(a3);
        alertStore.acknowledge(a3.getId());

        mvc.perform(get("/api/alerts/count"))
                .andExpect(status().isOk())
                .andExpect(content().string("2"));
    }

    // --- POST /api/alerts/{id}/acknowledge ---

    @Test
    void acknowledge_success() throws Exception {
        Alert alert = new Alert("LATENCY", Severity.CRITICAL, "test");
        alertStore.save(alert);

        mvc.perform(post("/api/alerts/" + alert.getId() + "/acknowledge"))
                .andExpect(status().isOk());

        assertAlertAcknowledged(alert.getId(), true);
    }

    @Test
    void acknowledge_notFound() throws Exception {
        mvc.perform(post("/api/alerts/999/acknowledge"))
                .andExpect(status().isNotFound());
    }

    // --- POST /api/alerts/acknowledge-all ---

    @Test
    void acknowledgeAll() throws Exception {
        alertStore.save(new Alert("LATENCY", Severity.CRITICAL, "one"));
        alertStore.save(new Alert("ACCURACY", Severity.WARN, "two"));

        mvc.perform(post("/api/alerts/acknowledge-all"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/alerts/unacknowledged"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // --- DELETE /api/alerts/{id} ---

    @Test
    void delete_success() throws Exception {
        Alert alert = new Alert("LATENCY", Severity.CRITICAL, "test");
        alertStore.save(alert);

        mvc.perform(delete("/api/alerts/" + alert.getId()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/alerts"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void delete_notFound() throws Exception {
        mvc.perform(delete("/api/alerts/999"))
                .andExpect(status().isNotFound());
    }

    // --- DELETE /api/alerts ---

    @Test
    void deleteAll() throws Exception {
        alertStore.save(new Alert("LATENCY", Severity.CRITICAL, "one"));
        alertStore.save(new Alert("ACCURACY", Severity.WARN, "two"));

        mvc.perform(delete("/api/alerts"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/alerts"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // --- Helpers ---

    private void assertAlertAcknowledged(long id, boolean expected) {
        Alert found = alertStore.findById(id).orElseThrow();
        assert found.isAcknowledged() == expected;
    }
}
