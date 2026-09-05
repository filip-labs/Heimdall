package dev.heimdall.deployment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateDeploymentStatusRequest(

        @NotNull
        DeploymentStatus status,

        @Size(max = 500)
        String failureReason

) {
}
