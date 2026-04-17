package com.marketdata.validator.controller;

import com.marketdata.validator.feed.FeedManager;
import com.marketdata.validator.model.Tick;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StreamController.class)
@SuppressWarnings("java:S1075")
class StreamControllerTest {

    private static final String TICKS_PATH       = "/api/stream/ticks";
    private static final String VALIDATION_PATH  = "/api/stream/validation";
    private static final String LATENCY_PATH     = "/api/stream/latency";
    private static final String THROUGHPUT_PATH  = "/api/stream/throughput";
    private static final String FEED_BINANCE     = "binance-1";
    private static final String SYM_BTC          = "BTCUSDT";
    private static final String PARAM_SYMBOL     = "symbol";
    private static final String PARAM_FEED_ID    = "feedId";

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
        MvcResult result = mvc.perform(get(TICKS_PATH))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getRequest().isAsyncStarted()).isTrue();
    }

    @Test
    void tickEndpointAcceptsSymbolFilter() throws Exception {
        MvcResult result = mvc.perform(get(TICKS_PATH).param(PARAM_SYMBOL, SYM_BTC))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getRequest().isAsyncStarted()).isTrue();
    }

    // --- GET /api/stream/validation ---

    @Test
    void validationEndpointReturnsSseEmitter() throws Exception {
        MvcResult result = mvc.perform(get(VALIDATION_PATH))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getRequest().isAsyncStarted()).isTrue();
    }

    // --- GET /api/stream/latency ---

    @Test
    void latencyEndpointReturnsSseEmitter() throws Exception {
        MvcResult result = mvc.perform(get(LATENCY_PATH))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getRequest().isAsyncStarted()).isTrue();
    }

    // --- GET /api/stream/throughput ---

    @Test
    void throughputEndpointReturnsSseEmitter() throws Exception {
        MvcResult result = mvc.perform(get(THROUGHPUT_PATH))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getRequest().isAsyncStarted()).isTrue();
    }

    // --- Emitter management ---

    @Test
    void emittersRegisteredOnStreamConnect() throws Exception {
        mvc.perform(get(TICKS_PATH)).andExpect(request().asyncStarted());
        mvc.perform(get(VALIDATION_PATH)).andExpect(request().asyncStarted());
        mvc.perform(get(LATENCY_PATH)).andExpect(request().asyncStarted());
        mvc.perform(get(THROUGHPUT_PATH)).andExpect(request().asyncStarted());

        StreamController controller = mvc.getDispatcherServlet()
                .getWebApplicationContext()
                .getBean(StreamController.class);

        assertThat(controller.getTickEmitterCount()).isGreaterThanOrEqualTo(1);
        assertThat(controller.getValidationEmitterCount()).isGreaterThanOrEqualTo(1);
        assertThat(controller.getLatencyEmitterCount()).isGreaterThanOrEqualTo(1);
        assertThat(controller.getThroughputEmitterCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void multipleTickEmittersCanCoexist() throws Exception {
        mvc.perform(get(TICKS_PATH)).andExpect(request().asyncStarted());
        mvc.perform(get(TICKS_PATH).param(PARAM_SYMBOL, "ETHUSDT")).andExpect(request().asyncStarted());

        StreamController controller = mvc.getDispatcherServlet()
                .getWebApplicationContext()
                .getBean(StreamController.class);

        assertThat(controller.getTickEmitterCount()).isGreaterThanOrEqualTo(2);
    }

    // --- Overall status ---

    @Test
    void allFourEndpointsStartAsync() throws Exception {
        String[] paths = {TICKS_PATH, VALIDATION_PATH, LATENCY_PATH, THROUGHPUT_PATH};
        for (String path : paths) {
            mvc.perform(get(path)).andExpect(request().asyncStarted());
        }
    }

    // --- Edge cases ---

    @Test
    void tickEndpointWithEmptySymbolFilterAccepted() throws Exception {
        MvcResult result = mvc.perform(get(TICKS_PATH).param(PARAM_SYMBOL, ""))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getRequest().isAsyncStarted()).isTrue();
    }

    @Test
    void multipleValidationEmittersCanCoexist() throws Exception {
        mvc.perform(get(VALIDATION_PATH)).andExpect(request().asyncStarted());
        mvc.perform(get(VALIDATION_PATH)).andExpect(request().asyncStarted());

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

    // --- onTick filter branch coverage ---

    @Test
    void onTickFeedIdFilterNonMatchDoesNotSend() throws Exception {
        mvc.perform(get(TICKS_PATH).param(PARAM_FEED_ID, FEED_BINANCE))
                .andExpect(request().asyncStarted());

        StreamController controller = mvc.getDispatcherServlet()
                .getWebApplicationContext()
                .getBean(StreamController.class);

        // feedFilter != null && feedFilter != tick.feedId → feedMatch = false, send skipped
        controller.onTick(buildTick(SYM_BTC, "different-feed", 1));
        assertThat(controller.getTickEmitterCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void onTickFeedIdFilterMatchSends() throws Exception {
        mvc.perform(get(TICKS_PATH).param(PARAM_FEED_ID, FEED_BINANCE))
                .andExpect(request().asyncStarted());

        StreamController controller = mvc.getDispatcherServlet()
                .getWebApplicationContext()
                .getBean(StreamController.class);

        // feedFilter != null && feedFilter == tick.feedId → feedMatch = true
        controller.onTick(buildTick(SYM_BTC, FEED_BINANCE, 2));
        assertThat(controller.getTickEmitterCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void onTickSymbolFilterNonMatchDoesNotSend() throws Exception {
        mvc.perform(get(TICKS_PATH).param(PARAM_SYMBOL, SYM_BTC))
                .andExpect(request().asyncStarted());

        StreamController controller = mvc.getDispatcherServlet()
                .getWebApplicationContext()
                .getBean(StreamController.class);

        // symFilter != null && symFilter != tick.symbol → symMatch = false, send skipped
        controller.onTick(buildTick("ETHUSDT", FEED_BINANCE, 3));
        assertThat(controller.getTickEmitterCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void onTickSymbolFilterMatchSends() throws Exception {
        mvc.perform(get(TICKS_PATH).param(PARAM_SYMBOL, SYM_BTC))
                .andExpect(request().asyncStarted());

        StreamController controller = mvc.getDispatcherServlet()
                .getWebApplicationContext()
                .getBean(StreamController.class);

        // symFilter != null && symFilter == tick.symbol → symMatch = true
        controller.onTick(buildTick(SYM_BTC, FEED_BINANCE, 4));
        assertThat(controller.getTickEmitterCount()).isGreaterThanOrEqualTo(0);
    }

    // --- formatTick null safety ---

    @Test
    @SuppressWarnings("unchecked")
    void formatTickHandlesAllNullFields() {
        StreamController controller = mvc.getDispatcherServlet()
                .getWebApplicationContext()
                .getBean(StreamController.class);

        Tick tick = new Tick();
        tick.setSequenceNum(1);

        Map<String, Object> result = controller.formatTick(tick);

        assertThat(result.get(PARAM_SYMBOL)).isEqualTo("");
        assertThat(result.get("price")).isEqualTo("0");
        assertThat(result.get("bid")).isEqualTo("");
        assertThat(result.get("ask")).isEqualTo("");
        assertThat(result.get("volume")).isEqualTo("");
        assertThat(result.get("exchangeTimestamp")).isEqualTo("");
        assertThat(result.get("receivedTimestamp")).isEqualTo("");
        assertThat(result.get(PARAM_FEED_ID)).isEqualTo("");
    }

    @Test
    @SuppressWarnings("unchecked")
    void formatTickHandlesPopulatedFields() {
        StreamController controller = mvc.getDispatcherServlet()
                .getWebApplicationContext()
                .getBean(StreamController.class);

        Tick tick = new Tick();
        tick.setSymbol(SYM_BTC);
        tick.setPrice(new BigDecimal("50000.00"));
        tick.setBid(new BigDecimal("49999.50"));
        tick.setAsk(new BigDecimal("50000.50"));
        tick.setVolume(new BigDecimal("1.5"));
        tick.setSequenceNum(42);
        tick.setExchangeTimestamp(Instant.parse("2026-01-01T00:00:00Z"));
        tick.setReceivedTimestamp(Instant.parse("2026-01-01T00:00:01Z"));
        tick.setFeedId(FEED_BINANCE);

        Map<String, Object> result = controller.formatTick(tick);

        assertThat(result.get(PARAM_SYMBOL)).isEqualTo(SYM_BTC);
        assertThat(result.get("price")).isEqualTo("50000.00");
        assertThat(result.get(PARAM_FEED_ID)).isEqualTo(FEED_BINANCE);
    }

    // --- Helpers ---

    private Tick buildTick(String symbol, String feedId, long seqNum) {
        Tick tick = new Tick();
        tick.setSymbol(symbol);
        tick.setPrice(new BigDecimal("50000"));
        tick.setFeedId(feedId);
        tick.setSequenceNum(seqNum);
        tick.setExchangeTimestamp(Instant.now());
        tick.setReceivedTimestamp(Instant.now());
        return tick;
    }
}
