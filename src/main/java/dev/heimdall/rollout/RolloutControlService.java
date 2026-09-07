package dev.heimdall.rollout;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class RolloutControlService {

    private final RolloutRepository rolloutRepository;

    public RolloutControlService(RolloutRepository rolloutRepository) {
        this.rolloutRepository = rolloutRepository;
    }

    @Transactional
    public RolloutResponse pause(UUID rolloutId) {
        return controlRollout(rolloutId, Rollout::pause);
    }

    @Transactional
    public RolloutResponse resume(UUID rolloutId) {
        return controlRollout(rolloutId, Rollout::resume);
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
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Rollout not found"
                ));

        try {
            action.apply(rollout);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    e.getMessage()
            );
        }

        return RolloutResponse.from(rollout);
    }

    @FunctionalInterface
    private interface RolloutControlAction {
        void apply(Rollout rollout);
    }
}
