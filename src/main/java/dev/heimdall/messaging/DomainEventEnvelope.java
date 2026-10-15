package dev.heimdall.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record DomainEventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String aggregateType,
        UUID aggregateId,
        Instant occurredAt,
        JsonNode payload
) {
}
