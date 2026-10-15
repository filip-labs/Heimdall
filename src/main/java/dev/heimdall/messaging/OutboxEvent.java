package dev.heimdall.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Getter
@Entity
@Table(name = "event_outbox")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "kafka_topic", nullable = false, length = 200)
    private String kafkaTopic;

    @Column(name = "kafka_key", nullable = false, length = 200)
    private String kafkaKey;

    @Column(nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    public OutboxEvent(
            DomainEventEnvelope envelope,
            String kafkaTopic,
            String kafkaKey,
            String serializedEnvelope
    ) {
        this.id = envelope.eventId();
        this.aggregateType = envelope.aggregateType();
        this.aggregateId = envelope.aggregateId();
        this.eventType = envelope.eventType();
        this.schemaVersion = envelope.schemaVersion();
        this.kafkaTopic = kafkaTopic;
        this.kafkaKey = kafkaKey;
        this.payload = serializedEnvelope;
        this.createdAt = envelope.occurredAt();
        this.attemptCount = 0;
        this.nextAttemptAt = envelope.occurredAt();
    }

    public void markPublished(Instant publishedAt) {
        this.publishedAt = publishedAt;
        this.lastError = null;
    }

    public void markFailed(String error, Instant failedAt) {
        this.attemptCount++;
        long backoffSeconds = Math.min(60, 1L << Math.min(attemptCount, 6));
        this.nextAttemptAt = failedAt.plus(backoffSeconds, ChronoUnit.SECONDS);
        this.lastError = error == null ? "Unknown Kafka publication failure"
                : error.substring(0, Math.min(error.length(), 1000));
    }
}
