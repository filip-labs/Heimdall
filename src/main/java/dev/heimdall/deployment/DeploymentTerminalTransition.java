package dev.heimdall.deployment;

import java.time.Duration;

public record DeploymentTerminalTransition(
        DeploymentStatus status,
        Duration duration
) {

    public static DeploymentTerminalTransition from(Deployment deployment) {
        return new DeploymentTerminalTransition(
                deployment.getStatus(),
                Duration.between(
                        deployment.getCreatedAt(),
                        deployment.getUpdatedAt()
                )
        );
    }
}
