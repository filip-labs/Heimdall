package dev.heimdall.deployment;

import java.time.Instant;
import java.util.UUID;

public record DeploymentEventResponse(
        UUID id,
        DeploymentStatus fromStatus,
        DeploymentStatus toStatus,
        String failureReason,
        Instant createdAt
) {

    public static DeploymentEventResponse from(DeploymentEvent event) {
        return new DeploymentEventResponse(
                event.getId(),
                event.getFromStatus(),
                event.getToStatus(),
                event.getFailureReason(),
                event.getCreatedAt()
        );
    }
}
