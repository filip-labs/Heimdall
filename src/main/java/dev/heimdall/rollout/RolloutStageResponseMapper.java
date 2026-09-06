package dev.heimdall.rollout;

import dev.heimdall.deployment.DeploymentRepository;
import dev.heimdall.deployment.DeploymentStatus;
import org.springframework.stereotype.Component;

@Component
public class RolloutStageResponseMapper {

    private final DeploymentRepository deploymentRepository;

    public RolloutStageResponseMapper(DeploymentRepository deploymentRepository) {
        this.deploymentRepository = deploymentRepository;
    }

    public RolloutStageResponse from(RolloutStage stage) {
        long deploymentCount = deploymentRepository.countByRolloutStage_Id(stage.getId());
        long installedCount = deploymentRepository.countByRolloutStage_IdAndStatus(
                stage.getId(),
                DeploymentStatus.INSTALLED
        );
        long failedCount = deploymentRepository.countByRolloutStage_IdAndStatus(
                stage.getId(),
                DeploymentStatus.FAILED
        );

        return new RolloutStageResponse(
                stage.getId(),
                stage.getStageIndex(),
                stage.getTargetPercentage(),
                stage.getTargetVehicleCount(),
                stage.getStatus(),
                deploymentCount,
                installedCount,
                failedCount,
                stage.getStartedAt(),
                stage.getCompletedAt()
        );
    }
}
