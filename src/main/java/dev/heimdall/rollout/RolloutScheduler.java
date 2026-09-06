package dev.heimdall.rollout;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RolloutScheduler {

    private final RolloutEvaluator rolloutEvaluator;

    public RolloutScheduler(RolloutEvaluator rolloutEvaluator) {
        this.rolloutEvaluator = rolloutEvaluator;
    }

    @Scheduled(
            fixedDelayString = "${heimdall.rollout.evaluation-interval-ms:1000}",
            initialDelayString = "${heimdall.rollout.evaluation-interval-ms:1000}"
    )
    public void evaluate() {
        rolloutEvaluator.evaluateRunningRollouts();
    }
}
