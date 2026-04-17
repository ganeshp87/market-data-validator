package com.marketdata.validator.controller;

import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.model.ValidationResult.Area;
import com.marketdata.validator.model.ValidationResult.Status;
import com.marketdata.validator.validator.ValidatorEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ValidationControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ValidatorEngine engine;

    // --- GET /api/validation/summary ---

    @Test
    void summaryReturnsAllFieldsWhenAllPass() throws Exception {
        when(engine.getResultsByArea()).thenReturn(Map.of(
                "ACCURACY", ValidationResult.pass(Area.ACCURACY, "OK", 0.01, 0.1),
                "LATENCY", ValidationResult.pass(Area.LATENCY, "OK", 42, 500)
        ));
        when(engine.getTickCount()).thenReturn(1000L);

        mvc.perform(get("/api/validation/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus", is("PASS")))
                .andExpect(jsonPath("$.ticksProcessed", is(1000)))
                .andExpect(jsonPath("$.results.ACCURACY.status", is("PASS")))
                .andExpect(jsonPath("$.results.LATENCY.status", is("PASS")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void summaryOverallStatusIsFailWhenAnyFails() throws Exception {
        when(engine.getResultsByArea()).thenReturn(Map.of(
                "ACCURACY", ValidationResult.pass(Area.ACCURACY, "OK", 0.01, 0.1),
                "LATENCY", ValidationResult.fail(Area.LATENCY, "p95 too high", 600, 500)
        ));
        when(engine.getTickCount()).thenReturn(500L);

        mvc.perform(get("/api/validation/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus", is("FAIL")));
    }

    @Test
    void summaryOverallStatusIsWarnWhenNoFailButWarn() throws Exception {
        when(engine.getResultsByArea()).thenReturn(Map.of(
                "ACCURACY", ValidationResult.pass(Area.ACCURACY, "OK", 0.01, 0.1),
                "LATENCY", ValidationResult.warn(Area.LATENCY, "borderline", 450, 500)
        ));
        when(engine.getTickCount()).thenReturn(300L);

        mvc.perform(get("/api/validation/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus", is("WARN")));
    }

    @Test
    void summaryReturnsEmptyResultsWhenNoValidators() throws Exception {
        when(engine.getResultsByArea()).thenReturn(Map.of());
        when(engine.getTickCount()).thenReturn(0L);

        mvc.perform(get("/api/validation/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus", is("PASS")))
                .andExpect(jsonPath("$.ticksProcessed", is(0)));
    }

    // --- GET /api/validation/history ---

    @Test
    void historyReturnsOrderedResults() throws Exception {
        when(engine.getResults()).thenReturn(List.of(
                ValidationResult.pass(Area.ACCURACY, "OK", 0.01, 0.1),
                ValidationResult.warn(Area.LATENCY, "high", 450, 500),
                ValidationResult.fail(Area.COMPLETENESS, "gaps", 5, 0)
        ));

        mvc.perform(get("/api/validation/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].area", is("ACCURACY")))
                .andExpect(jsonPath("$[1].area", is("LATENCY")))
                .andExpect(jsonPath("$[2].area", is("COMPLETENESS")));
    }

    @Test
    void historyReturnsEmptyListWhenNoResults() throws Exception {
        when(engine.getResults()).thenReturn(List.of());

        mvc.perform(get("/api/validation/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // --- PUT /api/validation/config ---

    @Test
    void configUpdatesSpecificArea() throws Exception {
        mvc.perform(put("/api/validation/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"area\":\"LATENCY\",\"config\":{\"warnThresholdMs\":200}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("Configuration updated")))
                .andExpect(jsonPath("$.area", is("LATENCY")));

        verify(engine).configure(eq("LATENCY"), anyMap());
    }

    @Test
    void configUpdatesAllWhenNoArea() throws Exception {
        mvc.perform(put("/api/validation/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"config\":{\"warnThresholdMs\":200}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.area", is("ALL")));

        verify(engine).configure(anyMap());
    }

    @Test
    void configReturns400WhenNoConfigMap() throws Exception {
        mvc.perform(put("/api/validation/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"area\":\"LATENCY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("config")));
    }

    @Test
    void configReturns400WhenConfigIsEmpty() throws Exception {
        mvc.perform(put("/api/validation/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"config\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("config")));
    }

    // --- POST /api/validation/reset ---

    @Test
    void resetCallsEngineReset() throws Exception {
        mvc.perform(post("/api/validation/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("reset")))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(engine).reset();
    }

    @Test
    void resetIsIdempotent() throws Exception {
        mvc.perform(post("/api/validation/reset"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/validation/reset"))
                .andExpect(status().isOk());

        verify(engine, times(2)).reset();
    }

    // --- Overall status computation ---

    @Test
    void failTakesPrecedenceOverWarn() throws Exception {
        when(engine.getResultsByArea()).thenReturn(Map.of(
                "ACCURACY", ValidationResult.warn(Area.ACCURACY, "warn", 0.08, 0.1),
                "LATENCY", ValidationResult.fail(Area.LATENCY, "fail", 600, 500),
                "ORDERING", ValidationResult.pass(Area.ORDERING, "ok", 0, 0)
        ));
        when(engine.getTickCount()).thenReturn(100L);

        mvc.perform(get("/api/validation/summary"))
                .andExpect(jsonPath("$.overallStatus", is("FAIL")));
    }
}
