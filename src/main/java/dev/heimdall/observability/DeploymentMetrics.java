package dev.heimdall.observability;

import dev.heimdall.deployment.DeploymentStatus;
import dev.heimdall.deployment.DeploymentTerminalTransition;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
@Component
public class DeploymentMetrics {

    private final Counter successCounter;
    private final Counter failureCounter;
    private final Timer successDuration;
    private final Timer failureDuration;

    public DeploymentMetrics(MeterRegistry meterRegistry) {
        this.successCounter = Counter.builder("heimdall.ota.success")
                .description("Committed successful OTA terminal transitions")
                .register(meterRegistry);
        this.failureCounter = Counter.builder("heimdall.ota.failure")
                .description("Committed failed OTA terminal transitions")
                .register(meterRegistry);
        this.successDuration = durationTimer(meterRegistry, "success");
        this.failureDuration = durationTimer(meterRegistry, "failure");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void record(DeploymentTerminalTransition transition) {
        Duration duration = nonNegative(transition.duration());

        switch (transition.status()) {
            case INSTALLED -> {
                successCounter.increment();
                successDuration.record(duration);
            }
            case FAILED -> {
                failureCounter.increment();
                failureDuration.record(duration);
            }
            default -> {
                // Only terminal transitions are instrumented.
            }
        }
    }

    private Timer durationTimer(MeterRegistry meterRegistry, String outcome) {
        return Timer.builder("heimdall.ota.duration")
                .description("Time from deployment creation to terminal OTA outcome")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private Duration nonNegative(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
