package dev.heimdall.rollout;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RolloutEvaluator {

    private final RolloutRepository rolloutRepository;
    private final RolloutEvaluationWorker evaluationWorker;

    public void evaluateRunningRollouts() {
        List<UUID> rolloutIds = rolloutRepository.findAllByStatus(RolloutStatus.RUNNING)
                .stream()
                .map(Rollout::getId)
                .toList();

        for (UUID rolloutId : rolloutIds) {
            evaluationWorker.evaluateRollout(rolloutId);
        }
    }

    public void evaluateRollout(UUID rolloutId) {
        evaluationWorker.evaluateRollout(rolloutId);
    }
}
