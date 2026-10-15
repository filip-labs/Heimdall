package dev.heimdall.messaging;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findByPublishedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            Instant now,
            Pageable pageable
    );

    long countByPublishedAtIsNull();

    long countByEventType(String eventType);
}
