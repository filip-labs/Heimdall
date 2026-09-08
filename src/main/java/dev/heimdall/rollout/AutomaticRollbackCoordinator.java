package dev.heimdall.rollout;

import dev.heimdall.api.ConflictException;
import dev.heimdall.deployment.DeploymentRepository;
import dev.heimdall.deployment.DeploymentRollbackService;
import dev.heimdall.deployment.DeploymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutomaticRollbackCoordinator {

    private final RolloutRepository rolloutRepository;
    private final RolloutStageRepository rolloutStageRepository;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentRollbackService deploymentRollbackService;

    public void processEligibleRollouts() {
        List<Rollout> rollouts = rolloutRepository
                .findAllByStatusAndAutomaticRollbackEnabledTrue(RolloutStatus.PAUSED);

        for (Rollout rollout : rollouts) {
            processRollout(rollout);
        }
    }

    private void processRollout(Rollout rollout) {
        RolloutStage stage = rolloutStageRepository
                .findByRollout_IdAndStageIndex(
                        rollout.getId(),
                        rollout.getCurrentStageIndex()
                )
                .orElseThrow(() -> new IllegalStateException("Current rollout stage not found"));

        if (stage.getStatus() != RolloutStageStatus.FAILED) {
            return;
        }

        List<UUID> sourceDeploymentIds = deploymentRepository
                .findIdsByRolloutStageIdAndStatusOrderByVehicleVinAsc(
                        stage.getId(),
                        DeploymentStatus.INSTALLED
                );

        for (UUID sourceDeploymentId : sourceDeploymentIds) {
            createRollback(sourceDeploymentId);
        }
    }

    private void createRollback(UUID sourceDeploymentId) {
        try {
            deploymentRollbackService.rollback(sourceDeploymentId);
        } catch (ConflictException exception) {
            log.warn(
                    "Automatic rollback skipped for deployment {}: {}",
                    sourceDeploymentId,
                    exception.getMessage()
            );
        }
    }
}
