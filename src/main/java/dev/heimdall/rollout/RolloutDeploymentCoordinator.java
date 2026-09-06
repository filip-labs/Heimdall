package dev.heimdall.rollout;

import dev.heimdall.deployment.DeploymentService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RolloutDeploymentCoordinator {

    private final RolloutStageRepository rolloutStageRepository;
    private final RolloutTargetRepository rolloutTargetRepository;
    private final DeploymentService deploymentService;

    public RolloutDeploymentCoordinator(
            RolloutStageRepository rolloutStageRepository,
            RolloutTargetRepository rolloutTargetRepository,
            DeploymentService deploymentService
    ) {
        this.rolloutStageRepository = rolloutStageRepository;
        this.rolloutTargetRepository = rolloutTargetRepository;
        this.deploymentService = deploymentService;
    }

    public void createDeploymentsForStage(
            Rollout rollout,
            RolloutStage stage
    ) {
        int previousTargetCount = previousTargetCount(rollout, stage);

        if (stage.getTargetVehicleCount() <= previousTargetCount) {
            return;
        }

        List<RolloutTarget> targets = rolloutTargetRepository
                .findAllByRollout_IdAndTargetOrdinalBetweenOrderByTargetOrdinalAsc(
                        rollout.getId(),
                        previousTargetCount + 1,
                        stage.getTargetVehicleCount()
                );

        for (RolloutTarget target : targets) {
            deploymentService.createForRolloutStage(
                    target.getVehicle(),
                    rollout.getRelease(),
                    stage
            );
        }
    }

    private int previousTargetCount(
            Rollout rollout,
            RolloutStage stage
    ) {
        if (stage.getStageIndex() == 0) {
            return 0;
        }

        return rolloutStageRepository
                .findByRollout_IdAndStageIndex(
                        rollout.getId(),
                        stage.getStageIndex() - 1
                )
                .orElseThrow(() -> new IllegalStateException("Previous rollout stage not found"))
                .getTargetVehicleCount();
    }
}
