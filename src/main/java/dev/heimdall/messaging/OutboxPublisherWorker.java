package dev.heimdall.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class OutboxPublisherWorker {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxMetrics outboxMetrics;

    @Transactional
    public void publish(UUID eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElse(null);
        if (event == null || event.getPublishedAt() != null) {
            return;
        }

        try {
            kafkaTemplate.send(
                    event.getKafkaTopic(),
                    event.getKafkaKey(),
                    event.getPayload().toString()
            ).get(10, TimeUnit.SECONDS);
            event.markPublished(Instant.now());
            outboxMetrics.published(event.getAggregateType());
        } catch (Exception exception) {
            event.markFailed(rootMessage(exception), Instant.now());
            outboxMetrics.publishFailed();
        }
    }

    private String rootMessage(Exception exception) {
        Throwable root = exception;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
