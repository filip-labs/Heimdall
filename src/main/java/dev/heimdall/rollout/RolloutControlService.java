package dev.heimdall.rollout;

import dev.heimdall.api.ConflictException;
import dev.heimdall.api.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RolloutControlService {

    private final RolloutRepository rolloutRepository;
    private final RolloutStageRepository rolloutStageRepository;

    @Transactional
    public RolloutResponse pause(UUID rolloutId) {
        return controlRollout(rolloutId, Rollout::pause);
    }

    @Transactional
    public RolloutResponse resume(UUID rolloutId) {
        return controlRollout(rolloutId, rollout -> {
            validateCurrentStageIsResumable(rollout);
            rollout.resume();
        });
    }

    @Transactional
    public RolloutResponse cancel(UUID rolloutId) {
        return controlRollout(rolloutId, Rollout::cancel);
    }

    private RolloutResponse controlRollout(
            UUID rolloutId,
            RolloutControlAction action
    ) {
        Rollout rollout = rolloutRepository.findByIdForUpdate(rolloutId)
                .orElseThrow(() -> new ResourceNotFoundException("Rollout not found"));

        try {
            action.apply(rollout);
        } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
        }

        return RolloutResponse.from(rollout);
    }

    private void validateCurrentStageIsResumable(Rollout rollout) {
        if (rollout.getStatus() != RolloutStatus.PAUSED) {
            return;
        }

        RolloutStage currentStage = rolloutStageRepository
                .findByRollout_IdAndStageIndex(
                        rollout.getId(),
                        rollout.getCurrentStageIndex()
                )
                .orElseThrow(() -> new IllegalStateException("Current rollout stage not found"));

        if (currentStage.getStatus() == RolloutStageStatus.FAILED) {
            throw new IllegalStateException(
                    "Rollout cannot be resumed because the current stage has failed"
            );
        }
    }

    @FunctionalInterface
    private interface RolloutControlAction {
        void apply(Rollout rollout);
    }
}
