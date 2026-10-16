package dev.heimdall.observability;

import dev.heimdall.deployment.DeploymentCreated;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PerformanceMetrics {

    private final Counter deploymentsCreated;
    private final Timer rolloutEvaluationDuration;

    public PerformanceMetrics(MeterRegistry meterRegistry) {
        this.deploymentsCreated = Counter.builder("heimdall.deployments.created")
                .description("Deployments created by committed transactions")
                .register(meterRegistry);
        this.rolloutEvaluationDuration = Timer.builder("heimdall.rollout.evaluation.duration")
                .description("Time spent evaluating a rollout")
                .register(meterRegistry);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void recordDeploymentCreated(DeploymentCreated ignored) {
        deploymentsCreated.increment();
    }

    public Timer rolloutEvaluationDuration() {
        return rolloutEvaluationDuration;
    }
}
