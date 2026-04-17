package com.marketdata.validator.validator;

import com.marketdata.validator.feed.FeedManager;
import com.marketdata.validator.model.Connection;
import com.marketdata.validator.model.Connection.AdapterType;
import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.model.ValidationResult.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SourceValidatorTest {

    private FeedManager feedManager;
    private SourceValidator validator;

    @BeforeEach
    void setUp() {
        feedManager = mock(FeedManager.class);
        validator = new SourceValidator(feedManager);
    }

    private Tick tick(String symbol, long seq, String feedId) {
        return new Tick(symbol, new BigDecimal("45000"), new BigDecimal("1"),
                seq, Instant.now(), feedId);
    }

    @Test
    void areaIsSOURCE() {
        assertThat(validator.getArea()).isEqualTo("SOURCE");
    }

    @Test
    void noTicksProcessedReturnsPass() {
        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(Status.PASS);
        assertThat(result.getMessage()).contains("No ticks");
    }

    @Test
    void knownFeedAndSubscribedSymbolPass() {
        Connection conn = new Connection("Binance", "wss://example.com", AdapterType.BINANCE,
                List.of("BTCUSDT", "ETHUSDT"));
        when(feedManager.getConnection("feed-1")).thenReturn(conn);

        validator.onTick(tick("BTCUSDT", 1, "feed-1"));
        validator.onTick(tick("ETHUSDT", 2, "feed-1"));

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(Status.PASS);
        assertThat((long) result.getDetails().get("matchedTicks")).isEqualTo(2);
    }

    @Test
    void unknownFeedIdCausesFailure() {
        when(feedManager.getConnection("unknown-feed")).thenReturn(null);

        for (int i = 1; i <= 100; i++) {
            validator.onTick(tick("BTCUSDT", i, "unknown-feed"));
        }

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(Status.FAIL);
        assertThat(result.getMetric()).isEqualTo(0.0);
        assertThat((long) result.getDetails().get("unknownFeedCount")).isEqualTo(100);
    }

    @Test
    void unsubscribedSymbolCausesFailure() {
        Connection conn = new Connection("Binance", "wss://example.com", AdapterType.BINANCE,
                List.of("BTCUSDT"));
        when(feedManager.getConnection("feed-1")).thenReturn(conn);

        for (int i = 1; i <= 100; i++) {
            validator.onTick(tick("XRPUSDT", i, "feed-1"));
        }

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(Status.FAIL);
        assertThat((long) result.getDetails().get("unknownSymbolCount")).isEqualTo(100);
    }

    @Test
    void emptySymbolListMeansAllSymbolsAllowed() {
        Connection conn = new Connection("Generic", "wss://example.com", AdapterType.GENERIC,
                List.of());
        when(feedManager.getConnection("feed-1")).thenReturn(conn);

        validator.onTick(tick("ANYSYMBOL", 1, "feed-1"));

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(Status.PASS);
        assertThat((long) result.getDetails().get("matchedTicks")).isEqualTo(1);
    }

    @Test
    void mixedKnownAndUnknownProducesWarn() {
        Connection conn = new Connection("Binance", "wss://example.com", AdapterType.BINANCE,
                List.of("BTCUSDT"));
        when(feedManager.getConnection("feed-1")).thenReturn(conn);
        when(feedManager.getConnection("unknown")).thenReturn(null);

        // 97 good ticks
        for (int i = 1; i <= 97; i++) {
            validator.onTick(tick("BTCUSDT", i, "feed-1"));
        }
        // 3 bad ticks (unknown feed)
        for (int i = 98; i <= 100; i++) {
            validator.onTick(tick("BTCUSDT", i, "unknown"));
        }

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(Status.WARN);
        assertThat(result.getMetric()).isCloseTo(97.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void idempotentSkipsDuplicateSequence() {
        Connection conn = new Connection("Binance", "wss://example.com", AdapterType.BINANCE,
                List.of("BTCUSDT"));
        when(feedManager.getConnection("feed-1")).thenReturn(conn);

        validator.onTick(tick("BTCUSDT", 1, "feed-1"));
        validator.onTick(tick("BTCUSDT", 1, "feed-1")); // duplicate

        ValidationResult result = validator.getResult();
        assertThat((long) result.getDetails().get("totalTicks")).isEqualTo(1);
    }

    @Test
    void resetClearsAllState() {
        when(feedManager.getConnection("unknown")).thenReturn(null);
        validator.onTick(tick("BTCUSDT", 1, "unknown"));
        assertThat(validator.getResult().getDetails().get("unknownFeedCount")).isEqualTo(1L);

        validator.reset();

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(Status.PASS);
        assertThat(result.getMessage()).contains("No ticks");
    }

    @Test
    void noArgConstructorAlwaysPasses() {
        SourceValidator perFeedInstance = new SourceValidator();
        perFeedInstance.onTick(tick("BTCUSDT", 1, "any-feed"));

        ValidationResult result = perFeedInstance.getResult();
        assertThat(result.getStatus()).isEqualTo(Status.PASS);
        assertThat(result.getMetric()).isEqualTo(100.0);
    }

    @Test
    void configureThresholds() {
        Connection conn = new Connection("Binance", "wss://example.com", AdapterType.BINANCE,
                List.of("BTCUSDT"));
        when(feedManager.getConnection("feed-1")).thenReturn(conn);
        when(feedManager.getConnection("unknown")).thenReturn(null);

        // Set very low thresholds
        validator.configure(java.util.Map.of("sourcePassThreshold", 50.0, "sourceWarnThreshold", 25.0));

        // 60% match rate
        for (int i = 1; i <= 6; i++) validator.onTick(tick("BTCUSDT", i, "feed-1"));
        for (int i = 7; i <= 10; i++) validator.onTick(tick("BTCUSDT", i, "unknown"));

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(Status.PASS); // 60% >= 50%
    }

    @Test
    void unknownFeedsTrackedInDetails() {
        when(feedManager.getConnection("bad-feed-1")).thenReturn(null);
        when(feedManager.getConnection("bad-feed-2")).thenReturn(null);

        validator.onTick(tick("BTCUSDT", 1, "bad-feed-1"));
        validator.onTick(tick("ETHUSDT", 2, "bad-feed-2"));

        ValidationResult result = validator.getResult();
        @SuppressWarnings("unchecked")
        List<String> unknownFeeds = (List<String>) result.getDetails().get("unknownFeeds");
        assertThat(unknownFeeds).containsExactlyInAnyOrder("bad-feed-1", "bad-feed-2");
    }
}
