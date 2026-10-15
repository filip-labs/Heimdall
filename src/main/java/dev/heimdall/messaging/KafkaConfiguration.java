package dev.heimdall.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name = "heimdall.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConfiguration {

    @Bean
    NewTopic vehicleEventsTopic() {
        return topic(HeimdallTopics.VEHICLE_EVENTS);
    }

    @Bean
    NewTopic deploymentEventsTopic() {
        return topic(HeimdallTopics.DEPLOYMENT_EVENTS);
    }

    @Bean
    NewTopic rolloutEventsTopic() {
        return topic(HeimdallTopics.ROLLOUT_EVENTS);
    }

    @Bean
    NewTopic deploymentEventsDlt() {
        return topic(HeimdallTopics.DEPLOYMENT_EVENTS_DLT);
    }

    @Bean
    NewTopic rolloutEventsDlt() {
        return topic(HeimdallTopics.ROLLOUT_EVENTS_DLT);
    }

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaOperations<String, String> operations) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                operations,
                (record, exception) -> new org.apache.kafka.common.TopicPartition(
                        record.topic() + ".DLT",
                        record.partition()
                )
        );
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 2L));
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
