package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatorEngineTest {

    private ValidatorEngine engine;
    private OrderingValidator orderingValidator;
    private AccuracyValidator accuracyValidator;
    private LatencyValidator latencyValidator;
    private CompletenessValidator completenessValidator;

    @BeforeEach
    void setUp() {
        orderingValidator = new OrderingValidator();
        accuracyValidator = new AccuracyValidator();
        latencyValidator = new LatencyValidator();
        completenessValidator = new CompletenessValidator();

        engine = new ValidatorEngine(List.of(
                orderingValidator, accuracyValidator,
                latencyValidator, completenessValidator));
    }

    // --- Basic setup ---

    @Test
    void validatorCountMatchesInjectedList() {
        assertThat(engine.getValidatorCount()).isEqualTo(4);
    }

    @Test
    void tickCountStartsAtZero() {
        assertThat(engine.getTickCount()).isEqualTo(0);
    }

    // --- Fan-out: every tick reaches all validators ---

    @Test
    void allValidatorsReceiveEveryTick() {
        Tick tick = createTick("BTCUSDT", "45000.00", 1);
        engine.onTick(tick);

        assertThat(engine.getTickCount()).isEqualTo(1);

        // Each validator should have processed 1 tick
        List<ValidationResult> results = engine.getResults();
        assertThat(results).hasSize(4);
    }

    @Test
    void multipleTicksFanOutToAll() {
        engine.onTick(createTick("BTCUSDT", "45000.00", 1));
        engine.onTick(createTick("BTCUSDT", "45100.00", 2));
        engine.onTick(createTick("BTCUSDT", "45200.00", 3));

        assertThat(engine.getTickCount()).isEqualTo(3);
    }

    // --- Aggregated results ---

    @Test
    void getResultsReturnsAllValidatorResults() {
        engine.onTick(createTick("BTCUSDT", "45000.00", 1));

        List<ValidationResult> results = engine.getResults();
        assertThat(results).hasSize(4);
        assertThat(results).extracting(r -> r.getArea().name())
                .containsExactlyInAnyOrder("ORDERING", "ACCURACY", "LATENCY", "COMPLETENESS");
    }

    @Test
    void getResultsByAreaReturnsMap() {
        engine.onTick(createTick("BTCUSDT", "45000.00", 1));

        Map<String, ValidationResult> resultsByArea = engine.getResultsByArea();
        assertThat(resultsByArea).containsKey("ORDERING");
        assertThat(resultsByArea).containsKey("ACCURACY");
        assertThat(resultsByArea).containsKey("LATENCY");
        assertThat(resultsByArea).containsKey("COMPLETENESS");
    }

    // --- Valid data → all PASS ---

    @Test
    void validDataProducesAllPass() {
        engine.onTick(createTick("BTCUSDT", "45000.00", 1));
        engine.onTick(createTick("BTCUSDT", "45010.00", 2));

        List<ValidationResult> results = engine.getResults();
        assertThat(results).allMatch(r -> r.getStatus() == ValidationResult.Status.PASS);
        assertThat(engine.hasFailures()).isFalse();
    }

    // --- One bad validator detected ---

    @Test
    void invalidPriceCausesAccuracyFail() {
        // Feed many valid, then enough invalid to drop below 99%
        for (int i = 1; i <= 50; i++) {
            engine.onTick(createTick("BTCUSDT", "45000.00", i));
        }
        // 50 invalid prices → accuracy drops well below 99%
        for (int i = 51; i <= 100; i++) {
            engine.onTick(createTick("BTCUSDT", "0", i));
        }

        assertThat(engine.hasFailures()).isTrue();

        Map<String, ValidationResult> byArea = engine.getResultsByArea();
        assertThat(byArea.get("ACCURACY").getStatus()).isEqualTo(ValidationResult.Status.FAIL);
    }

    // --- Exception isolation ---

    @Test
    void brokenValidatorDoesNotStopOthers() {
        // Create a validator that throws on every tick
        Validator brokenValidator = new Validator() {
            @Override public String getArea() { return "BROKEN"; }
            @Override public void onTick(Tick tick) { throw new RuntimeException("Boom!"); }
            @Override public ValidationResult getResult() {
                return ValidationResult.pass(ValidationResult.Area.ORDERING, "Broken", 0, 0);
            }
            @Override public void reset() { }
            @Override public void configure(Map<String, Object> config) { }
        };

        ValidatorEngine engineWithBroken = new ValidatorEngine(
                List.of(brokenValidator, orderingValidator));

        // Should not throw — broken validator is caught, ordering still works
        engineWithBroken.onTick(createTick("BTCUSDT", "45000.00", 1));

        assertThat(engineWithBroken.getTickCount()).isEqualTo(1);
    }

    // --- Reset ---

    @Test
    void resetClearsAllState() {
        engine.onTick(createTick("BTCUSDT", "45000.00", 1));
        engine.onTick(createTick("BTCUSDT", "45100.00", 2));

        assertThat(engine.getTickCount()).isEqualTo(2);

        engine.reset();

        assertThat(engine.getTickCount()).isEqualTo(0);

        // All validators should report PASS after reset
        List<ValidationResult> results = engine.getResults();
        assertThat(results).allMatch(r -> r.getStatus() == ValidationResult.Status.PASS);
    }

    // --- Configure propagation ---

    @Test
    void configurePropagatesToAllValidators() {
        // Configure with a custom pass threshold
        engine.configure(Map.of("passThreshold", 50.0, "warnThreshold", 25.0));

        // Feed invalid data — with relaxed thresholds, should still PASS
        engine.onTick(createTick("BTCUSDT", "45000.00", 1));
        engine.onTick(createTick("BTCUSDT", "0", 2));

        // Ordering validator should have accepted threshold change
        Map<String, ValidationResult> results = engine.getResultsByArea();
        assertThat(results.get("ORDERING").getStatus()).isEqualTo(ValidationResult.Status.PASS);
    }

    @Test
    void configureByAreaTargetsOneValidator() {
        engine.configure("ORDERING", Map.of("passThreshold", 50.0));

        // Only ordering validator should be affected
        engine.onTick(createTick("BTCUSDT", "45000.00", 1));

        // This just verifies no exception — full threshold test is in OrderingValidatorTest
        assertThat(engine.getTickCount()).isEqualTo(1);
    }

    // --- Listeners ---

    @Test
    void listenerNotifiedOnEveryTick() {
        AtomicInteger callCount = new AtomicInteger(0);
        engine.addListener(results -> callCount.incrementAndGet());

        engine.onTick(createTick("BTCUSDT", "45000.00", 1));
        engine.onTick(createTick("BTCUSDT", "45100.00", 2));

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void listenerReceivesAllResults() {
        List<List<ValidationResult>> captured = new ArrayList<>();
        engine.addListener(captured::add);

        engine.onTick(createTick("BTCUSDT", "45000.00", 1));

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0)).hasSize(4); // 4 validators
    }

    @Test
    void removeListenerStopsNotifications() {
        AtomicInteger callCount = new AtomicInteger(0);
        var listener = new java.util.function.Consumer<List<ValidationResult>>() {
            @Override
            public void accept(List<ValidationResult> results) {
                callCount.incrementAndGet();
            }
        };

        engine.addListener(listener);
        engine.onTick(createTick("BTCUSDT", "45000.00", 1));
        assertThat(callCount.get()).isEqualTo(1);

        engine.removeListener(listener);
        engine.onTick(createTick("BTCUSDT", "45100.00", 2));
        assertThat(callCount.get()).isEqualTo(1); // Still 1 — not called again
    }

    @Test
    void brokenListenerDoesNotStopOthers() {
        AtomicInteger goodCount = new AtomicInteger(0);
        engine.addListener(results -> { throw new RuntimeException("Listener error"); });
        engine.addListener(results -> goodCount.incrementAndGet());

        engine.onTick(createTick("BTCUSDT", "45000.00", 1));

        assertThat(goodCount.get()).isEqualTo(1); // Second listener still called
    }

    // --- hasFailures ---

    @Test
    void hasFailuresReturnsFalseWhenAllPass() {
        engine.onTick(createTick("BTCUSDT", "45000.00", 1));
        assertThat(engine.hasFailures()).isFalse();
    }

    // --- Empty engine ---

    @Test
    void emptyEngineWorksWithNoValidators() {
        ValidatorEngine emptyEngine = new ValidatorEngine(List.of());

        emptyEngine.onTick(createTick("BTCUSDT", "45000.00", 1));

        assertThat(emptyEngine.getTickCount()).isEqualTo(1);
        assertThat(emptyEngine.getResults()).isEmpty();
        assertThat(emptyEngine.hasFailures()).isFalse();
    }

    // --- Edge cases: malformed ticks ---

    @Test
    void nullPriceTickHandledGracefully() {
        Tick tick = new Tick("BTCUSDT", null, new BigDecimal("1"),
                1, Instant.now(), "test-feed");
        tick.setReceivedTimestamp(Instant.now());

        // Should not throw — validators must handle null price internally
        engine.onTick(tick);
        assertThat(engine.getTickCount()).isEqualTo(1);
    }

    @Test
    void nullSymbolTickHandledGracefully() {
        Tick tick = new Tick(null, new BigDecimal("45000"), new BigDecimal("1"),
                1, Instant.now(), "test-feed");
        tick.setReceivedTimestamp(Instant.now());

        engine.onTick(tick);
        assertThat(engine.getTickCount()).isEqualTo(1);
    }

    @Test
    void nullTimestampTickHandledGracefully() {
        Tick tick = new Tick("BTCUSDT", new BigDecimal("45000"), new BigDecimal("1"),
                1, null, "test-feed");

        engine.onTick(tick);
        assertThat(engine.getTickCount()).isEqualTo(1);
    }

    @Test
    void highVolumeBurstProcessedCorrectly() {
        for (int i = 1; i <= 1000; i++) {
            engine.onTick(createTick("BTCUSDT", "45000.00", i));
        }
        assertThat(engine.getTickCount()).isEqualTo(1000);
        assertThat(engine.getResults()).hasSize(4);
        assertThat(engine.getResults()).allMatch(
                r -> r.getStatus() != null);
    }

    @Test
    void allEightValidatorsWiredCorrectly() {
        ValidatorEngine fullEngine = new ValidatorEngine(List.of(
                new OrderingValidator(),
                new AccuracyValidator(),
                new LatencyValidator(),
                new CompletenessValidator(),
                new ThroughputValidator(),
                new ReconnectionValidator(),
                new SubscriptionValidator(),
                new StatefulValidator()));

        assertThat(fullEngine.getValidatorCount()).isEqualTo(8);

        fullEngine.onTick(createTick("BTCUSDT", "45000.00", 1));

        Map<String, ValidationResult> byArea = fullEngine.getResultsByArea();
        assertThat(byArea).containsKeys(
                "ORDERING", "ACCURACY", "LATENCY", "COMPLETENESS",
                "THROUGHPUT", "RECONNECTION", "SUBSCRIPTION", "STATEFUL");
    }

    @Test
    void multipleSymbolsProcessedIndependently() {
        engine.onTick(createTick("BTCUSDT", "45000.00", 1));
        engine.onTick(createTick("ETHUSDT", "3000.00", 1));
        engine.onTick(createTick("BTCUSDT", "45100.00", 2));
        engine.onTick(createTick("ETHUSDT", "3010.00", 2));

        assertThat(engine.getTickCount()).isEqualTo(4);
        assertThat(engine.getResults()).allMatch(
                r -> r.getStatus() == ValidationResult.Status.PASS);
    }

    @Test
    void resetAfterManyTicksReleasesState() {
        for (int i = 1; i <= 500; i++) {
            engine.onTick(createTick("BTCUSDT", "45000.00", i));
        }
        assertThat(engine.getTickCount()).isEqualTo(500);

        engine.reset();
        assertThat(engine.getTickCount()).isEqualTo(0);

        // Post-reset: same sequence numbers should be re-processable
        engine.onTick(createTick("BTCUSDT", "45000.00", 1));
        assertThat(engine.getTickCount()).isEqualTo(1);
    }

    // --- Helpers ---

    private Tick createTick(String symbol, String price, long seqNum) {
        Instant exchangeTs = Instant.parse("2026-03-23T10:00:00Z").plusMillis(seqNum * 100);
        Tick tick = new Tick(symbol, new BigDecimal(price), new BigDecimal("1"),
                seqNum, exchangeTs, "test-feed");
        tick.setReceivedTimestamp(exchangeTs.plusMillis(50));
        return tick;
    }
}
