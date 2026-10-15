package dev.heimdall.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherWorkerTest {

    @Test
    void shouldKeepFailedPublicationPendingAndScheduleRetry() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        OutboxMetrics metrics = mock(OutboxMetrics.class);
        Instant occurredAt = Instant.now();
        UUID aggregateId = UUID.randomUUID();
        DomainEventEnvelope envelope = new DomainEventEnvelope(
                UUID.randomUUID(),
                "vehicle.registered",
                1,
                "vehicle",
                aggregateId,
                occurredAt,
                new ObjectMapper().valueToTree(Map.of("vin", "7FC90000000000099"))
        );
        OutboxEvent event = new OutboxEvent(
                envelope,
                HeimdallTopics.VEHICLE_EVENTS,
                aggregateId.toString(),
                new ObjectMapper().valueToTree(envelope)
        );

        when(repository.findById(event.getId())).thenReturn(Optional.of(event));
        when(kafkaTemplate.send(event.getKafkaTopic(), event.getKafkaKey(), event.getPayload().toString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        new OutboxPublisherWorker(repository, kafkaTemplate, metrics).publish(event.getId());

        assertNull(event.getPublishedAt());
        assertEquals(1, event.getAttemptCount());
        assertTrue(event.getNextAttemptAt().isAfter(occurredAt));
        assertEquals("broker unavailable", event.getLastError());
        verify(metrics).publishFailed();
    }
}

