package dev.heimdall.rollout;

import dev.heimdall.api.ResourceNotFoundException;
import dev.heimdall.deployment.DeploymentRepository;
import dev.heimdall.deployment.DeploymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RolloutEvaluationWorker {

    private static final List<DeploymentStatus> TERMINAL_STATUSES =
            List.of(DeploymentStatus.INSTALLED, DeploymentStatus.FAILED);

    private final RolloutRepository rolloutRepository;
    private final RolloutStageRepository rolloutStageRepository;
    private final DeploymentRepository deploymentRepository;
    private final RolloutDeploymentCoordinator deploymentCoordinator;

    @Transactional
    public void evaluateRollout(UUID rolloutId) {
        Rollout rollout = rolloutRepository.findByIdForUpdate(rolloutId)
                .orElseThrow(() -> new ResourceNotFoundException("Rollout not found"));

        if (rollout.getStatus() != RolloutStatus.RUNNING) {
            return;
        }

        RolloutStage stage = currentRunningStage(rollout);
        if (stage == null) {
            return;
        }

        StageHealth stageHealth = evaluateStageHealth(stage);
        if (!stageHealth.terminal()) {
            return;
        }

        // The health gate uses only the stage's incremental deployment cohort.
        if (stageHealth.failureRate().compareTo(rollout.getFailureThresholdPercent()) > 0) {
            stage.fail();
            rollout.pause();
            return;
        }

        completeStageAndMaybeAdvance(rollout, stage);
    }

    private RolloutStage currentRunningStage(Rollout rollout) {
        RolloutStage stage = rolloutStageRepository
                .findByRollout_IdAndStageIndex(
                        rollout.getId(),
                        rollout.getCurrentStageIndex()
                )
                .orElseThrow(() -> new IllegalStateException("Current rollout stage not found"));

        return stage.getStatus() == RolloutStageStatus.RUNNING ? stage : null;
    }

    private StageHealth evaluateStageHealth(RolloutStage stage) {
        long deploymentCount = deploymentRepository.countByRolloutStage_Id(stage.getId());
        long terminalCount = deploymentRepository.countByRolloutStage_IdAndStatusIn(
                stage.getId(),
                TERMINAL_STATUSES
        );

        if (terminalCount < deploymentCount) {
            return new StageHealth(false, BigDecimal.ZERO);
        }

        long failedCount = deploymentRepository.countByRolloutStage_IdAndStatus(
                stage.getId(),
                DeploymentStatus.FAILED
        );
        BigDecimal failureRate = deploymentCount == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(failedCount)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(deploymentCount), 4, RoundingMode.HALF_UP);

        return new StageHealth(true, failureRate);
    }

    private void completeStageAndMaybeAdvance(
            Rollout rollout,
            RolloutStage stage
    ) {
        stage.complete();

        List<RolloutStage> stages = rolloutStageRepository
                .findAllByRollout_IdOrderByStageIndexAsc(rollout.getId());
        int nextStageIndex = rollout.getCurrentStageIndex() + 1;
        if (nextStageIndex >= stages.size()) {
            rollout.complete();
            return;
        }

        RolloutStage nextStage = stages.get(nextStageIndex);
        nextStage.start();
        rollout.advanceToStage(nextStageIndex);
        deploymentCoordinator.createDeploymentsForStage(rollout, nextStage);
    }

    private record StageHealth(
            boolean terminal,
            BigDecimal failureRate
    ) {
    }
}
