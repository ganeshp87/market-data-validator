package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionValidatorTest {

    private SubscriptionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SubscriptionValidator();
        // Use short thresholds for testing
        validator.configure(Map.of(
                "subscribeTimeoutMs", 100L,
                "unsubscribeGraceMs", 50L,
                "activeThresholdMs", 500L
        ));
    }

    // --- Area ---

    @Test
    void areaIsSubscription() {
        assertThat(validator.getArea()).isEqualTo("SUBSCRIPTION");
    }

    // --- No subscriptions → PASS ---

    @Test
    void noSubscriptionsProducesPass() {
        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
        assertThat(result.getMessage()).contains("No subscriptions");
    }

    // --- Subscribe tracking ---

    @Test
    void subscribeAddsSymbol() {
        validator.onSubscribe("BTCUSDT");

        assertThat(validator.getSubscribedSymbols()).contains("BTCUSDT");
        assertThat(validator.getSubscribeEventCount()).isEqualTo(1);
    }

    @Test
    void multipleSubscribes() {
        validator.onSubscribe("BTCUSDT");
        validator.onSubscribe("ETHUSDT");

        assertThat(validator.getSubscribedSymbols()).containsExactlyInAnyOrder("BTCUSDT", "ETHUSDT");
        assertThat(validator.getSubscribeEventCount()).isEqualTo(2);
    }

    // --- Unsubscribe tracking ---

    @Test
    void unsubscribeRemovesSymbol() {
        validator.onSubscribe("BTCUSDT");
        validator.onUnsubscribe("BTCUSDT");

        assertThat(validator.getSubscribedSymbols()).doesNotContain("BTCUSDT");
        assertThat(validator.getUnsubscribeEventCount()).isEqualTo(1);
    }

    // --- Active symbol tracking via ticks ---

    @Test
    void tickMarksSymbolActive() {
        validator.onSubscribe("BTCUSDT");
        feedTick("BTCUSDT", 1);

        assertThat(validator.getActiveSymbols()).contains("BTCUSDT");
    }

    @Test
    void totalTicksCounted() {
        feedTick("BTCUSDT", 1);
        feedTick("ETHUSDT", 1);
        feedTick("BTCUSDT", 2);

        assertThat(validator.getTotalTicks()).isEqualTo(3);
    }

    // --- Idempotency ---

    @Test
    void skipsDuplicateSequenceNumber() {
        feedTick("BTCUSDT", 5);
        feedTick("BTCUSDT", 5); // duplicate

        assertThat(validator.getTotalTicks()).isEqualTo(1);
    }

    @Test
    void skipsOlderSequenceNumber() {
        feedTick("BTCUSDT", 10);
        feedTick("BTCUSDT", 8); // older

        assertThat(validator.getTotalTicks()).isEqualTo(1);
    }

    @Test
    void sequenceTrackingIsPerSymbol() {
        feedTick("BTCUSDT", 5);
        feedTick("ETHUSDT", 5); // different symbol — should count

        assertThat(validator.getTotalTicks()).isEqualTo(2);
    }

    // --- Subscribe latency ---

    @Test
    void recordsSubscribeLatency() {
        validator.onSubscribe("BTCUSDT");
        feedTick("BTCUSDT", 1);

        Map<String, Long> latencies = validator.getSubscribeLatencies();
        assertThat(latencies).containsKey("BTCUSDT");
        assertThat(latencies.get("BTCUSDT")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void subscribeLatencyRecordedOnlyOnce() {
        validator.onSubscribe("BTCUSDT");
        feedTick("BTCUSDT", 1);
        feedTick("BTCUSDT", 2);

        // Latency should be recorded from first tick only
        Map<String, Long> latencies = validator.getSubscribeLatencies();
        assertThat(latencies).hasSize(1);
    }

    // --- Leaky unsubscribes ---

    @Test
    void detectsLeakyUnsubscribe() throws InterruptedException {
        validator.onSubscribe("BTCUSDT");
        feedTick("BTCUSDT", 1);

        validator.onUnsubscribe("BTCUSDT");

        // Wait past the grace period (50ms in test config)
        Thread.sleep(80);

        feedTick("BTCUSDT", 2); // Tick arrives AFTER grace period → leak!

        assertThat(validator.getLeakyUnsubscribes()).contains("BTCUSDT");
    }

    @Test
    void tickWithinGracePeriodIsNotLeaky() {
        validator.onSubscribe("BTCUSDT");
        feedTick("BTCUSDT", 1);

        validator.onUnsubscribe("BTCUSDT");

        // Immediately send tick — within grace period
        feedTick("BTCUSDT", 2);

        assertThat(validator.getLeakyUnsubscribes()).isEmpty();
    }

    @Test
    void resubscribeClearsLeakyState() throws InterruptedException {
        validator.onSubscribe("BTCUSDT");
        feedTick("BTCUSDT", 1);
        validator.onUnsubscribe("BTCUSDT");

        Thread.sleep(80);
        feedTick("BTCUSDT", 2); // Leaky

        assertThat(validator.getLeakyUnsubscribes()).contains("BTCUSDT");

        // Re-subscribe should clear the leaky state
        validator.onSubscribe("BTCUSDT");
        assertThat(validator.getLeakyUnsubscribes()).isEmpty();
    }

    // --- Result: PASS ---

    @Test
    void allActiveProducesPass() {
        validator.onSubscribe("BTCUSDT");
        validator.onSubscribe("ETHUSDT");
        feedTick("BTCUSDT", 1);
        feedTick("ETHUSDT", 1);

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.PASS);
        assertThat(result.getMetric()).isEqualTo(100.0);
        assertThat(result.getMessage()).contains("All 2 subscribed symbols active");
    }

    // --- Result: WARN ---

    @Test
    void partiallyActiveProducesWarn() {
        validator.onSubscribe("BTCUSDT");
        validator.onSubscribe("ETHUSDT");
        feedTick("BTCUSDT", 1);
        // ETHUSDT has no ticks

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.WARN);
        assertThat(result.getMessage()).contains("Active: 1/2");
    }

    @Test
    void subscribeTimeoutProducesWarn() throws InterruptedException {
        validator.onSubscribe("BTCUSDT");

        // Wait past the subscribe timeout (100ms in test config)
        Thread.sleep(150);

        // Still no tick → timed out
        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.WARN);
        assertThat(result.getMessage()).contains("timedOut");
    }

    // --- Result: FAIL ---

    @Test
    void leakyUnsubscribeProducesFail() throws InterruptedException {
        validator.onSubscribe("BTCUSDT");
        feedTick("BTCUSDT", 1);
        validator.onUnsubscribe("BTCUSDT");

        Thread.sleep(80);
        feedTick("BTCUSDT", 2); // Leaky

        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.FAIL);
        assertThat(result.getMessage()).contains("Leaky");
    }

    @Test
    void allInactiveProducesFail() throws InterruptedException {
        validator.configure(Map.of("activeThresholdMs", 50L));
        validator.onSubscribe("BTCUSDT");
        feedTick("BTCUSDT", 1);

        // Wait for tick to become stale
        Thread.sleep(80);

        // Now subscribed but not active (last tick is stale)
        ValidationResult result = validator.getResult();
        assertThat(result.getStatus()).isEqualTo(ValidationResult.Status.FAIL);
        assertThat(result.getMessage()).contains("inactive");
    }

    // --- Details ---

    @Test
    void resultDetailsContainsExpectedKeys() {
        validator.onSubscribe("BTCUSDT");
        feedTick("BTCUSDT", 1);

        Map<String, Object> details = validator.getResult().getDetails();
        assertThat(details).containsKeys(
                "subscribedSymbols", "activeCount", "subscribedCount",
                "leakyUnsubscribes", "timedOutCount", "subscribeEvents",
                "unsubscribeEvents", "totalTicks"
        );
    }

    @Test
    void detailsHaveCorrectCounts() {
        validator.onSubscribe("BTCUSDT");
        validator.onSubscribe("ETHUSDT");
        feedTick("BTCUSDT", 1);

        Map<String, Object> details = validator.getResult().getDetails();
        assertThat(details.get("subscribedCount")).isEqualTo(2);
        assertThat(details.get("activeCount")).isEqualTo(1);
        assertThat(details.get("subscribeEvents")).isEqualTo(2L);
        assertThat(details.get("totalTicks")).isEqualTo(1L);
    }

    // --- Reset ---

    @Test
    void resetClearsAllState() {
        validator.onSubscribe("BTCUSDT");
        feedTick("BTCUSDT", 1);
        validator.onUnsubscribe("BTCUSDT");

        validator.reset();

        assertThat(validator.getSubscribedSymbols()).isEmpty();
        assertThat(validator.getActiveSymbols()).isEmpty();
        assertThat(validator.getLeakyUnsubscribes()).isEmpty();
        assertThat(validator.getSubscribeLatencies()).isEmpty();
        assertThat(validator.getTotalTicks()).isEqualTo(0);
        assertThat(validator.getSubscribeEventCount()).isEqualTo(0);
        assertThat(validator.getUnsubscribeEventCount()).isEqualTo(0);
    }

    @Test
    void resetAllowsSameSequenceToBeProcessedAgain() {
        feedTick("BTCUSDT", 1);
        assertThat(validator.getTotalTicks()).isEqualTo(1);

        validator.reset();
        feedTick("BTCUSDT", 1); // Same seqNum — should count again after reset
        assertThat(validator.getTotalTicks()).isEqualTo(1);
    }

    // --- Configure ---

    @Test
    void configureThresholds() {
        validator.configure(Map.of(
                "subscribeTimeoutMs", 200L,
                "unsubscribeGraceMs", 100L,
                "activeThresholdMs", 1000L
        ));

        // Verify no errors — configure is mostly setter-based
        assertThat(validator.getArea()).isEqualTo("SUBSCRIPTION");
    }

    // --- Helpers ---

    private void feedTick(String symbol, long seqNum) {
        Tick tick = new Tick(symbol, new BigDecimal("45000.00"),
                new BigDecimal("1"), seqNum, Instant.now(), "test-feed");
        validator.onTick(tick);
    }
}
