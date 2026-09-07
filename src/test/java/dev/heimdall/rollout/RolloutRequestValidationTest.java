package dev.heimdall.rollout;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolloutRequestValidationTest {

    private final Validator validator = Validation
            .buildDefaultValidatorFactory()
            .getValidator();

    @Test
    void shouldAcceptStrictlyIncreasingStagesEndingAtOneHundred() {
        assertTrue(validator.validate(request(List.of(5, 25, 50, 100))).isEmpty());
    }

    @Test
    void shouldRejectDecreasingStages() {
        assertFalse(validator.validate(request(List.of(25, 5, 100))).isEmpty());
    }

    @Test
    void shouldRejectStagesThatDoNotEndAtOneHundred() {
        assertFalse(validator.validate(request(List.of(5, 25, 50))).isEmpty());
    }

    @Test
    void shouldRejectStageBelowOne() {
        assertFalse(validator.validate(request(List.of(0, 100))).isEmpty());
    }

    @Test
    void shouldRejectStageAboveOneHundred() {
        assertFalse(validator.validate(request(List.of(101))).isEmpty());
    }

    private CreateRolloutRequest request(List<Integer> stages) {
        return new CreateRolloutRequest(
                UUID.randomUUID(),
                stages,
                BigDecimal.valueOf(5.0),
                null
        );
    }
}
