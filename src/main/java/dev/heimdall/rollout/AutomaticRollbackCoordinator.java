package dev.heimdall.rollout;

import dev.heimdall.api.ConflictException;
import dev.heimdall.deployment.DeploymentRollbackService;
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
    private final AutomaticRollbackWorker automaticRollbackWorker;
    private final DeploymentRollbackService deploymentRollbackService;

    public void processEligibleRollouts() {
        List<UUID> rolloutIds = rolloutRepository
                .findIdsByStatusAndAutomaticRollbackEnabledTrue(RolloutStatus.PAUSED);

        for (UUID rolloutId : rolloutIds) {
            processRollout(rolloutId);
        }
    }

    private void processRollout(UUID rolloutId) {
        List<UUID> sourceDeploymentIds = automaticRollbackWorker
                .findEligibleSourceDeployments(rolloutId);

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
