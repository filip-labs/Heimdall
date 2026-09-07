package dev.heimdall.rollout;

import dev.heimdall.deployment.DeploymentRepository;
import dev.heimdall.deployment.DeploymentRollbackService;
import dev.heimdall.deployment.DeploymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class AutomaticRollbackCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutomaticRollbackCoordinator.class);

    private final RolloutRepository rolloutRepository;
    private final RolloutStageRepository rolloutStageRepository;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentRollbackService deploymentRollbackService;

    public AutomaticRollbackCoordinator(
            RolloutRepository rolloutRepository,
            RolloutStageRepository rolloutStageRepository,
            DeploymentRepository deploymentRepository,
            DeploymentRollbackService deploymentRollbackService
    ) {
        this.rolloutRepository = rolloutRepository;
        this.rolloutStageRepository = rolloutStageRepository;
        this.deploymentRepository = deploymentRepository;
        this.deploymentRollbackService = deploymentRollbackService;
    }

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
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.CONFLICT) {
                LOGGER.warn(
                        "Automatic rollback skipped for deployment {}: {}",
                        sourceDeploymentId,
                        exception.getReason()
                );
                return;
            }

            throw exception;
        }
    }
}
