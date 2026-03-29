package com.marketdata.validator.controller;

import com.marketdata.validator.feed.FeedManager;
import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.store.AlertStore;
import com.marketdata.validator.validator.ValidatorEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StreamController.class)
class StreamControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private FeedManager feedManager;

    @MockBean
    private ValidatorEngine engine;

    @MockBean
    private AlertStore alertStore;

    // --- GET /api/stream/ticks ---

    @Test
    void tickEndpointReturnsSseEmitter() throws Exception {
        MvcResult result = mvc.perform(get("/api/stream/ticks"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // The response should be set up for SSE streaming
        assertThat(result.getRequest().isAsyncStarted()).isTrue();
    }

    @Test
    void tickEndpointAcceptsSymbolFilter() throws Exception {
        MvcResult result = mvc.perform(get("/api/stream/ticks").param("symbol", "BTCUSDT"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getRequest().isAsyncStarted()).isTrue();
    }

    // --- GET /api/stream/validation ---

    @Test
    void validationEndpointReturnsSseEmitter() throws Exception {
        MvcResult result = mvc.perform(get("/api/stream/validation"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getRequest().isAsyncStarted()).isTrue();
    }

    // --- GET /api/stream/latency ---

    @Test
    void latencyEndpointReturnsSseEmitter() throws Exception {
        MvcResult result = mvc.perform(get("/api/stream/latency"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getRequest().isAsyncStarted()).isTrue();
    }

    // --- GET /api/stream/throughput ---

    @Test
    void throughputEndpointReturnsSseEmitter() throws Exception {
        MvcResult result = mvc.perform(get("/api/stream/throughput"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getRequest().isAsyncStarted()).isTrue();
    }

    // --- Emitter management ---

    @Test
    void emittersRegisteredOnStreamConnect() throws Exception {
        mvc.perform(get("/api/stream/ticks"))
                .andExpect(request().asyncStarted());
        mvc.perform(get("/api/stream/validation"))
                .andExpect(request().asyncStarted());
        mvc.perform(get("/api/stream/latency"))
                .andExpect(request().asyncStarted());
        mvc.perform(get("/api/stream/throughput"))
                .andExpect(request().asyncStarted());

        // Controller should have registered emitters (accessible via package-private getters)
        StreamController controller = mvc.getDispatcherServlet()
                .getWebApplicationContext()
                .getBean(StreamController.class);

        assertThat(controller.getTickEmitterCount()).isGreaterThanOrEqualTo(1);
        assertThat(controller.getValidationEmitterCount()).isGreaterThanOrEqualTo(1);
        assertThat(controller.getLatencyEmitterCount()).isGreaterThanOrEqualTo(1);
        assertThat(controller.getThroughputEmitterCount()).isGreaterThanOrEqualTo(1);
    }

    // --- Tick formatting ---

    @Test
    void multipleTickEmittersCanCoexist() throws Exception {
        mvc.perform(get("/api/stream/ticks"))
                .andExpect(request().asyncStarted());
        mvc.perform(get("/api/stream/ticks").param("symbol", "ETHUSDT"))
                .andExpect(request().asyncStarted());

        StreamController controller = mvc.getDispatcherServlet()
                .getWebApplicationContext()
                .getBean(StreamController.class);

        assertThat(controller.getTickEmitterCount()).isGreaterThanOrEqualTo(2);
    }

    // --- Overall status ---

    @Test
    void allFourEndpointsStartAsync() throws Exception {
        String[] paths = {"/api/stream/ticks", "/api/stream/validation",
                "/api/stream/latency", "/api/stream/throughput"};

        for (String path : paths) {
            mvc.perform(get(path))
                    .andExpect(request().asyncStarted());
        }
    }

    // --- Edge cases ---

    @Test
    void tickEndpointWithEmptySymbolFilterAccepted() throws Exception {
        MvcResult result = mvc.perform(get("/api/stream/ticks").param("symbol", ""))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getRequest().isAsyncStarted()).isTrue();
    }

    @Test
    void multipleValidationEmittersCanCoexist() throws Exception {
        mvc.perform(get("/api/stream/validation"))
                .andExpect(request().asyncStarted());
        mvc.perform(get("/api/stream/validation"))
                .andExpect(request().asyncStarted());

        StreamController controller = mvc.getDispatcherServlet()
                .getWebApplicationContext()
                .getBean(StreamController.class);

        assertThat(controller.getValidationEmitterCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void unknownStreamPathReturns404() throws Exception {
        mvc.perform(get("/api/stream/nonexistent"))
                .andExpect(status().isNotFound());
    }
}
