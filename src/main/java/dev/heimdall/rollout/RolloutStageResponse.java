package dev.heimdall.rollout;

import java.time.Instant;
import java.util.UUID;

public record RolloutStageResponse(
        UUID id,
        int stageIndex,
        int targetPercentage,
        int targetVehicleCount,
        RolloutStageStatus status,
        long deploymentCount,
        long installedCount,
        long failedCount,
        Instant startedAt,
        Instant completedAt
) {
}
