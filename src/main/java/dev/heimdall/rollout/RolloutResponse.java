package dev.heimdall.rollout;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RolloutResponse(
        UUID id,
        UUID releaseId,
        String targetSoftwareVersion,
        RolloutStatus status,
        BigDecimal failureThresholdPercent,
        boolean automaticRollbackEnabled,
        int totalVehicles,
        int currentStageIndex,
        Instant createdAt,
        Instant updatedAt
) {

    public static RolloutResponse from(Rollout rollout) {
        return new RolloutResponse(
                rollout.getId(),
                rollout.getRelease().getId(),
                rollout.getRelease().getVersion(),
                rollout.getStatus(),
                rollout.getFailureThresholdPercent(),
                rollout.isAutomaticRollbackEnabled(),
                rollout.getTotalVehicles(),
                rollout.getCurrentStageIndex(),
                rollout.getCreatedAt(),
                rollout.getUpdatedAt()
        );
    }
}
