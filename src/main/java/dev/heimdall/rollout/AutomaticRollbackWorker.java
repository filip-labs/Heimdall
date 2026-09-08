package dev.heimdall.rollout;

import dev.heimdall.api.ResourceNotFoundException;
import dev.heimdall.deployment.DeploymentRepository;
import dev.heimdall.deployment.DeploymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AutomaticRollbackWorker {

    private final RolloutRepository rolloutRepository;
    private final RolloutStageRepository rolloutStageRepository;
    private final DeploymentRepository deploymentRepository;

    @Transactional
    public List<UUID> findEligibleSourceDeployments(UUID rolloutId) {
        Rollout rollout = rolloutRepository.findByIdForUpdate(rolloutId)
                .orElseThrow(() -> new ResourceNotFoundException("Rollout not found"));

        if (!isEligibleRollout(rollout)) {
            return List.of();
        }

        RolloutStage stage = rolloutStageRepository
                .findByRollout_IdAndStageIndex(
                        rollout.getId(),
                        rollout.getCurrentStageIndex()
                )
                .orElseThrow(() -> new IllegalStateException("Current rollout stage not found"));

        if (stage.getStatus() != RolloutStageStatus.FAILED) {
            return List.of();
        }

        return deploymentRepository
                .findIdsByRolloutStageIdAndStatusOrderByVehicleVinAsc(
                        stage.getId(),
                        DeploymentStatus.INSTALLED
                );
    }

    private boolean isEligibleRollout(Rollout rollout) {
        return rollout.getStatus() == RolloutStatus.PAUSED
                && rollout.isAutomaticRollbackEnabled();
    }
}
