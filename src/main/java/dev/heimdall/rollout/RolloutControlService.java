package dev.heimdall.rollout;

import dev.heimdall.api.ConflictException;
import dev.heimdall.api.ResourceNotFoundException;
import dev.heimdall.messaging.DomainEventRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RolloutControlService {

    private final RolloutRepository rolloutRepository;
    private final RolloutStageRepository rolloutStageRepository;
    private final DomainEventRecorder eventRecorder;

    @Transactional
    public RolloutResponse pause(UUID rolloutId) {
        return controlRollout(rolloutId, Rollout::pause, "pause");
    }

    @Transactional
    public RolloutResponse resume(UUID rolloutId) {
        return controlRollout(rolloutId, rollout -> {
            validateCurrentStageIsResumable(rollout);
            rollout.resume();
        }, "resume");
    }

    @Transactional
    public RolloutResponse cancel(UUID rolloutId) {
        return controlRollout(rolloutId, Rollout::cancel, "cancel");
    }

    private RolloutResponse controlRollout(
            UUID rolloutId,
            RolloutControlAction action,
            String operation
    ) {
        Rollout rollout = rolloutRepository.findByIdForUpdate(rolloutId)
                .orElseThrow(() -> new ResourceNotFoundException("Rollout not found"));

        RolloutStatus previousStatus = rollout.getStatus();
        try {
            action.apply(rollout);
        } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
        }

        if (rollout.getStatus() != previousStatus) {
            switch (operation) {
                case "pause" -> eventRecorder.rolloutPaused(rollout, "OPERATOR");
                case "resume" -> eventRecorder.rolloutResumed(rollout);
                case "cancel" -> eventRecorder.rolloutCancelled(rollout);
                default -> throw new IllegalStateException("Unknown rollout control operation");
            }
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
