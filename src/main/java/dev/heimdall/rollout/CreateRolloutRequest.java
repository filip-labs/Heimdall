package dev.heimdall.rollout;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreateRolloutRequest(

        @NotNull
        UUID releaseId,

        @NotEmpty
        List<@NotNull @Min(1) @Max(100) Integer> stages,

        @NotNull
        @DecimalMin("0.0")
        @DecimalMax("100.0")
        BigDecimal failureThresholdPercent,

        Boolean automaticRollbackEnabled
) {

    @AssertTrue(message = "stages must be strictly increasing and end at 100")
    public boolean isValidStages() {
        if (stages == null || stages.isEmpty()) {
            return true;
        }

        int previousStage = 0;
        for (Integer stage : stages) {
            if (stage == null || stage <= previousStage || stage < 1 || stage > 100) {
                return false;
            }
            previousStage = stage;
        }

        return stages.getLast() == 100;
    }
}
