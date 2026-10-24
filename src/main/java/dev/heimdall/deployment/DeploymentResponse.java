package dev.heimdall.deployment;

import java.time.Instant;
import java.util.UUID;

public record DeploymentResponse(
        UUID id,

        UUID vehicleId,
        String vin,
        String sourceSoftwareVersion,

        UUID releaseId,
        String targetSoftwareVersion,
        String artifactUrl,
        String checksum,
        String signatureAlgorithm,
        String signingKeyId,
        String signature,

        DeploymentStatus status,
        String failureReason,
        UUID rollbackOfDeploymentId,
        UUID rolloutId,
        UUID rolloutStageId,

        Instant createdAt,
        Instant updatedAt
) {

    public static DeploymentResponse from(Deployment deployment) {
        return new DeploymentResponse(
                deployment.getId(),

                deployment.getVehicle().getId(),
                deployment.getVehicle().getVin(),
                deployment.getSourceSoftwareVersion(),

                deployment.getRelease().getId(),
                deployment.getRelease().getVersion(),
                deployment.getRelease().getArtifactUrl(),
                deployment.getRelease().getChecksum(),
                deployment.getRelease().getSignatureAlgorithm(),
                deployment.getRelease().getSigningKeyId(),
                deployment.getRelease().getSignature(),

                deployment.getStatus(),
                deployment.getFailureReason(),
                deployment.getRollbackOfDeployment() == null
                        ? null
                        : deployment.getRollbackOfDeployment().getId(),
                deployment.getRolloutStage() == null ? null : deployment.getRolloutStage().getRollout().getId(),
                deployment.getRolloutStage() == null ? null : deployment.getRolloutStage().getId(),

                deployment.getCreatedAt(),
                deployment.getUpdatedAt()
        );
    }
}
