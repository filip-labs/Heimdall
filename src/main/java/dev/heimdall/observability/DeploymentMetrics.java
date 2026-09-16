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
import java.util.EnumMap;
import java.util.Map;

@Component
public class DeploymentMetrics {

    private final Map<DeploymentStatus, Counter> outcomeCounters;
    private final Map<DeploymentStatus, Timer> durationTimers;

    public DeploymentMetrics(MeterRegistry meterRegistry) {
        this.outcomeCounters = new EnumMap<>(DeploymentStatus.class);
        this.durationTimers = new EnumMap<>(DeploymentStatus.class);

        registerOutcome(meterRegistry, DeploymentStatus.INSTALLED, "success");
        registerOutcome(meterRegistry, DeploymentStatus.FAILED, "failure");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void record(DeploymentTerminalTransition transition) {
        Counter counter = outcomeCounters.get(transition.status());
        Timer timer = durationTimers.get(transition.status());

        if (counter == null || timer == null) {
            return;
        }

        counter.increment();
        timer.record(nonNegative(transition.duration()));
    }

    private void registerOutcome(
            MeterRegistry meterRegistry,
            DeploymentStatus status,
            String outcome
    ) {
        outcomeCounters.put(
                status,
                Counter.builder("heimdall.ota.outcomes")
                        .description("Committed OTA terminal outcomes")
                        .tag("outcome", outcome)
                        .register(meterRegistry)
        );
        durationTimers.put(
                status,
                Timer.builder("heimdall.ota.duration")
                        .description("Time from deployment creation to terminal OTA outcome")
                        .tag("outcome", outcome)
                        .register(meterRegistry)
        );
    }

    private Duration nonNegative(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
