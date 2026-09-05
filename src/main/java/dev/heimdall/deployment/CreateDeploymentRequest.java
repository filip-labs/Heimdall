package dev.heimdall.deployment;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateDeploymentRequest(

        @NotNull
        UUID vehicleId,

        @NotNull
        UUID releaseId

) {
}
