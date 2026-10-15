package dev.heimdall.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OutboxMetrics {

    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> publishedCounters = new ConcurrentHashMap<>();
    private final Counter failureCounter;

    public OutboxMetrics(MeterRegistry meterRegistry, OutboxEventRepository repository) {
        this.meterRegistry = meterRegistry;
        this.failureCounter = Counter.builder("heimdall.outbox.publish.failures")
                .description("Kafka publication attempts that failed")
                .register(meterRegistry);
        Gauge.builder("heimdall.outbox.pending", repository, OutboxEventRepository::countByPublishedAtIsNull)
                .description("Durable outbox events awaiting publication")
                .register(meterRegistry);
    }

    public void published(String aggregateType) {
        publishedCounters.computeIfAbsent(aggregateType, type -> Counter
                        .builder("heimdall.outbox.published")
                        .tag("aggregate", type)
                        .description("Outbox events published to Kafka")
                        .register(meterRegistry))
                .increment();
    }

    public void publishFailed() {
        failureCounter.increment();
    }
}
