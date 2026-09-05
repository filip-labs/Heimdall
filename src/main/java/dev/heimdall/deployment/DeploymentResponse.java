package dev.heimdall.deployment;

import java.time.Instant;
import java.util.UUID;

public record DeploymentResponse(
        UUID id,

        UUID vehicleId,
        String vin,
        String currentSoftwareVersion,

        UUID releaseId,
        String targetSoftwareVersion,

        DeploymentStatus status,
        String failureReason,

        Instant createdAt,
        Instant updatedAt
) {

    public static DeploymentResponse from(Deployment deployment) {
        return new DeploymentResponse(
                deployment.getId(),

                deployment.getVehicle().getId(),
                deployment.getVehicle().getVin(),
                deployment.getVehicle().getSoftwareVersion(),

                deployment.getRelease().getId(),
                deployment.getRelease().getVersion(),

                deployment.getStatus(),
                deployment.getFailureReason(),

                deployment.getCreatedAt(),
                deployment.getUpdatedAt()
        );
    }
}
