package com.marketdata.validator.validator;

import com.marketdata.validator.model.Alert;
import com.marketdata.validator.model.ValidationResult;
import com.marketdata.validator.store.AlertStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AlertGeneratorTest {

    private ValidatorEngine engine;
    private AlertStore alertStore;
    private AlertGenerator alertGenerator;

    @BeforeEach
    void setUp() {
        engine = mock(ValidatorEngine.class);
        alertStore = mock(AlertStore.class);
        alertGenerator = new AlertGenerator(engine, alertStore);
        // Do NOT call init() in each test — tests that need it call it explicitly.
    }

    // --- Fix 1: Timer not advanced on DB query failure ---

    @Test
    void timerNotAdvancedWhenDbQueryFails() {
        when(alertStore.findUnacknowledged()).thenThrow(new RuntimeException("DB down"));

        List<ValidationResult> failResults = List.of(
                new ValidationResult(ValidationResult.Area.ACCURACY,
                        ValidationResult.Status.FAIL, "test fail", 0.0, 100.0));

        // Call 1: lastCheckTime=0, so refresh is triggered; DB throws → timer reset to 0
        alertGenerator.onValidationResults(failResults);

        // Timer must still be near 0 (not advanced to "now")
        assertThat(alertGenerator.getLastCheckTime())
                .as("timer must not be advanced when DB query fails")
                .isLessThan(System.currentTimeMillis() - 9_000);
    }

    @Test
    void timerResetOnFailureCausesRetryOnNextCall() {
        // DB always throws
        when(alertStore.findUnacknowledged()).thenThrow(new RuntimeException("DB down"));

        List<ValidationResult> failResults = List.of(
                new ValidationResult(ValidationResult.Area.ACCURACY,
                        ValidationResult.Status.FAIL, "test fail", 0.0, 100.0));

        // Two rapid consecutive calls — with the fix, BOTH should attempt DB refresh
        // because the timer is reset to 0 after the first failure.
        alertGenerator.onValidationResults(failResults); // call 1 → timer reset after failure
        alertGenerator.onValidationResults(failResults); // call 2 → retries because timer still ~0

        // Both calls must have attempted a DB query (not just the first one)
        verify(alertStore, times(2)).findUnacknowledged();
    }

    @Test
    void timerAdvancedOnSuccessfulDbQuery() {
        when(alertStore.findUnacknowledged()).thenReturn(List.of());

        List<ValidationResult> passResults = List.of(
                new ValidationResult(ValidationResult.Area.ACCURACY,
                        ValidationResult.Status.PASS, "ok", 100.0, 99.0));

        alertGenerator.onValidationResults(passResults);

        // Timer should now be near System.currentTimeMillis() (not still 0)
        assertThat(alertGenerator.getLastCheckTime())
                .as("timer must be advanced after a successful DB query")
                .isGreaterThan(System.currentTimeMillis() - 1_000);
    }

    // --- Fix 4: Per-feed listener registered on init ---

    @Test
    void perFeedListenerRegisteredOnInit() {
        when(alertStore.findUnacknowledged()).thenReturn(List.of());

        alertGenerator.init();

        verify(engine).addPerFeedListener(any());
    }

    @Test
    void perFeedValidationFailGeneratesAlert() {
        // Capture the per-feed listener registered during init
        when(alertStore.findUnacknowledged()).thenReturn(List.of());

        final BiConsumer<String, List<ValidationResult>>[] captured = new BiConsumer[1];
        doAnswer(inv -> {
            captured[0] = inv.getArgument(0);
            return null;
        }).when(engine).addPerFeedListener(any());
        doNothing().when(engine).addListener(any());

        alertGenerator.init();

        assertThat(captured[0]).as("per-feed listener must be registered").isNotNull();

        // Simulate per-feed ACCURACY FAIL from feed-1
        List<ValidationResult> failResults = List.of(
                new ValidationResult(ValidationResult.Area.ACCURACY,
                        ValidationResult.Status.FAIL, "per-feed accuracy fail", 50.0, 99.0));

        captured[0].accept("feed-1", failResults);

        // Alert must be saved
        verify(alertStore).save(any(Alert.class));
    }

    @Test
    void perFeedValidationPassDoesNotGenerateAlert() {
        when(alertStore.findUnacknowledged()).thenReturn(List.of());

        final BiConsumer<String, List<ValidationResult>>[] captured = new BiConsumer[1];
        doAnswer(inv -> {
            captured[0] = inv.getArgument(0);
            return null;
        }).when(engine).addPerFeedListener(any());
        doNothing().when(engine).addListener(any());

        alertGenerator.init();

        List<ValidationResult> passResults = List.of(
                new ValidationResult(ValidationResult.Area.ACCURACY,
                        ValidationResult.Status.PASS, "all good", 100.0, 99.0));

        captured[0].accept("feed-1", passResults);

        verify(alertStore, never()).save(any());
    }
}
