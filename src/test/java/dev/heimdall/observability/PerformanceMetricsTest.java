package dev.heimdall.observability;

import dev.heimdall.deployment.DeploymentCreated;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceMetricsTest {

    @Test
    void shouldRecordDeploymentCreationAndRolloutEvaluationDuration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PerformanceMetrics metrics = new PerformanceMetrics(registry);

        metrics.recordDeploymentCreated(new DeploymentCreated());
        metrics.rolloutEvaluationDuration().record(Duration.ofMillis(25));

        assertThat(registry.get("heimdall.deployments.created").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("heimdall.rollout.evaluation.duration").timer().count())
                .isEqualTo(1);
    }
}
