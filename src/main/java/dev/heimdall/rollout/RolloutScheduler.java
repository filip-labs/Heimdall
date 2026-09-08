package dev.heimdall.rollout;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RolloutScheduler {

    private final RolloutEvaluator rolloutEvaluator;
    private final AutomaticRollbackCoordinator automaticRollbackCoordinator;

    @Scheduled(
            fixedDelayString = "${heimdall.rollout.evaluation-interval:1s}",
            initialDelayString = "${heimdall.rollout.evaluation-interval:1s}"
    )
    public void evaluate() {
        rolloutEvaluator.evaluateRunningRollouts();
        automaticRollbackCoordinator.processEligibleRollouts();
    }
}
