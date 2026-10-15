package dev.heimdall.messaging;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private static final int SCHEMA_VERSION = 1;

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public UUID append(
            String topic,
            String eventType,
            String aggregateType,
            UUID aggregateId,
            Map<String, ?> payload
    ) {
        DomainEventEnvelope envelope = new DomainEventEnvelope(
                UUID.randomUUID(),
                eventType,
                SCHEMA_VERSION,
                aggregateType,
                aggregateId,
                Instant.now(),
                objectMapper.valueToTree(payload)
        );

        try {
            outboxEventRepository.save(new OutboxEvent(
                    envelope,
                    topic,
                    aggregateId.toString(),
                    objectMapper.writeValueAsString(envelope)
            ));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize domain event", exception);
        }

        return envelope.eventId();
    }
}
