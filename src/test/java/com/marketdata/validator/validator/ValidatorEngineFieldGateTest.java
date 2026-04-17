package com.marketdata.validator.validator;

import com.marketdata.validator.model.Tick;
import com.marketdata.validator.model.ValidationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that ValidatorEngine correctly uses FieldCompletenessValidator as a pre-validation gate
 * and exposes rejectedCount.
 */
class ValidatorEngineFieldGateTest {

    private Tick validTick(long seq) {
        return new Tick("BTCUSDT", new BigDecimal("45000"), new BigDecimal("1"),
                seq, Instant.now(), "feed-1");
    }

    @Test
    void validTicksPassThroughGate() {
        FieldCompletenessValidator gate = new FieldCompletenessValidator();
        AccuracyValidator accuracy = new AccuracyValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(accuracy), gate);

        engine.onTick(validTick(1));
        engine.onTick(validTick(2));

        assertThat(engine.getTickCount()).isEqualTo(2);
        assertThat(engine.getRejectedCount()).isZero();
    }

    @Test
    void invalidTicksRejectedByGate() {
        FieldCompletenessValidator gate = new FieldCompletenessValidator();
        AccuracyValidator accuracy = new AccuracyValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(accuracy), gate);

        Tick bad = validTick(1);
        bad.setSymbol(null);
        engine.onTick(bad);

        assertThat(engine.getTickCount()).isZero();
        assertThat(engine.getRejectedCount()).isEqualTo(1);
    }

    @Test
    void rejectedTicksNotForwardedToValidators() {
        FieldCompletenessValidator gate = new FieldCompletenessValidator();
        AccuracyValidator accuracy = new AccuracyValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(accuracy), gate);

        // Send 3 valid + 2 invalid
        engine.onTick(validTick(1));
        engine.onTick(validTick(2));

        Tick nullPrice = validTick(3);
        nullPrice.setPrice(null);
        engine.onTick(nullPrice);

        engine.onTick(validTick(4));

        Tick nullFeed = validTick(5);
        nullFeed.setFeedId(null);
        engine.onTick(nullFeed);

        assertThat(engine.getTickCount()).isEqualTo(3);
        assertThat(engine.getRejectedCount()).isEqualTo(2);
    }

    @Test
    void resetClearsRejectedCount() {
        FieldCompletenessValidator gate = new FieldCompletenessValidator();
        ValidatorEngine engine = new ValidatorEngine(List.of(new AccuracyValidator()), gate);

        Tick bad = validTick(1);
        bad.setSymbol(null);
        engine.onTick(bad);
        assertThat(engine.getRejectedCount()).isEqualTo(1);

        engine.reset();
        assertThat(engine.getRejectedCount()).isZero();
    }

    @Test
    void noGateConstructorAlwaysAccepts() {
        ValidatorEngine engine = new ValidatorEngine(List.of(new AccuracyValidator()));

        Tick bad = validTick(1);
        bad.setSymbol(null); // Would be rejected with gate, but no gate here
        engine.onTick(bad);

        assertThat(engine.getTickCount()).isEqualTo(1);
        assertThat(engine.getRejectedCount()).isZero();
    }
}
