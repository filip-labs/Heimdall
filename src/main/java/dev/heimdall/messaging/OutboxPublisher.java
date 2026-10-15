package dev.heimdall.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "heimdall.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxPublisherWorker publisherWorker;

    @Value("${heimdall.kafka.outbox-batch-size:50}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${heimdall.kafka.outbox-publish-interval:1s}",
            initialDelayString = "${heimdall.kafka.outbox-publish-interval:1s}"
    )
    public void publishPending() {
        List<UUID> eventIds = outboxEventRepository
                .findByPublishedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        Instant.now(),
                        PageRequest.of(0, batchSize)
                )
                .stream()
                .map(OutboxEvent::getId)
                .toList();

        eventIds.forEach(publisherWorker::publish);
    }
}
