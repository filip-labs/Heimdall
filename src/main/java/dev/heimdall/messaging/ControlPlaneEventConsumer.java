package dev.heimdall.messaging;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.heimdall.rollout.AutomaticRollbackCoordinator;
import dev.heimdall.rollout.RolloutEvaluator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "heimdall.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class ControlPlaneEventConsumer {

    private static final Set<String> TERMINAL_STATUSES = Set.of("INSTALLED", "FAILED");

    private final ObjectMapper objectMapper;
    private final RolloutEvaluator rolloutEvaluator;
    private final AutomaticRollbackCoordinator automaticRollbackCoordinator;
    private final Counter processingFailures;

    public ControlPlaneEventConsumer(
            ObjectMapper objectMapper,
            RolloutEvaluator rolloutEvaluator,
            AutomaticRollbackCoordinator automaticRollbackCoordinator,
            MeterRegistry meterRegistry
    ) {
        this.objectMapper = objectMapper;
        this.rolloutEvaluator = rolloutEvaluator;
        this.automaticRollbackCoordinator = automaticRollbackCoordinator;
        this.processingFailures = Counter.builder("heimdall.kafka.consumer.failures")
                .description("Control-plane Kafka events that failed processing")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = HeimdallTopics.DEPLOYMENT_EVENTS,
            groupId = "heimdall-rollout-evaluation"
    )
    public void onDeploymentEvent(String serializedEnvelope) {
        try {
            JsonNode envelope = objectMapper.readTree(serializedEnvelope);
            if (!"deployment.status.changed".equals(envelope.path("eventType").asText())) {
                return;
            }

            JsonNode payload = envelope.path("payload");
            if (!TERMINAL_STATUSES.contains(payload.path("toStatus").asText())) {
                return;
            }

            JsonNode rolloutId = payload.path("rolloutId");
            if (rolloutId.isMissingNode() || rolloutId.isNull()) {
                return;
            }

            rolloutEvaluator.evaluateRollout(UUID.fromString(rolloutId.asText()));
        } catch (RuntimeException exception) {
            processingFailures.increment();
            throw new IllegalArgumentException("Could not process deployment event", exception);
        }
    }

    @KafkaListener(
            topics = HeimdallTopics.ROLLOUT_EVENTS,
            groupId = "heimdall-automatic-rollback"
    )
    public void onRolloutEvent(String serializedEnvelope) {
        try {
            JsonNode envelope = objectMapper.readTree(serializedEnvelope);
            if (!"rollout.paused".equals(envelope.path("eventType").asText())) {
                return;
            }

            JsonNode payload = envelope.path("payload");
            if (!"HEALTH_GATE".equals(payload.path("reason").asText())) {
                return;
            }

            automaticRollbackCoordinator.processRollout(
                    UUID.fromString(payload.path("rolloutId").asText())
            );
        } catch (RuntimeException exception) {
            processingFailures.increment();
            throw new IllegalArgumentException("Could not process rollout event", exception);
        }
    }
}
