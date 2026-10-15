package dev.heimdall.messaging;

import tools.jackson.databind.ObjectMapper;
import dev.heimdall.deployment.CreateDeploymentRequest;
import dev.heimdall.deployment.DeploymentResponse;
import dev.heimdall.deployment.DeploymentService;
import dev.heimdall.deployment.DeploymentStatus;
import dev.heimdall.deployment.UpdateDeploymentStatusRequest;
import dev.heimdall.release.SoftwareRelease;
import dev.heimdall.release.SoftwareReleaseRepository;
import dev.heimdall.rollout.RolloutEvaluator;
import dev.heimdall.vehicle.CreateVehicleRequest;
import dev.heimdall.vehicle.VehicleResponse;
import dev.heimdall.vehicle.VehicleService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {
        "heimdall.kafka.enabled=true",
        "heimdall.kafka.outbox-publish-interval=3600s",
        "heimdall.rollout.evaluation-interval=3600s"
})
@Testcontainers
class M9KafkaIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Container
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.2.2");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private VehicleService vehicleService;

    @Autowired
    private DeploymentService deploymentService;

    @Autowired
    private SoftwareReleaseRepository releaseRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RolloutEvaluator rolloutEvaluator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    event_outbox,
                    rollout,
                    deployment_event,
                    deployment,
                    software_release,
                    vehicle
                CASCADE
                """);
    }

    @Test
    void shouldProvisionRequiredTopics() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()
        ))) {
            Set<String> topics = admin.listTopics().names().get();
            assertEquals(true, topics.containsAll(Set.of(
                    HeimdallTopics.VEHICLE_EVENTS,
                    HeimdallTopics.DEPLOYMENT_EVENTS,
                    HeimdallTopics.ROLLOUT_EVENTS,
                    HeimdallTopics.DEPLOYMENT_EVENTS_DLT,
                    HeimdallTopics.ROLLOUT_EVENTS_DLT
            )));
        }
    }

    @Test
    void shouldPersistDomainStateAndOutboxAtomically() {
        vehicleService.create(new CreateVehicleRequest("7FC90000000000001", "1.0.0"));
        assertEquals(1, outboxRepository.countByEventType("vehicle.registered"));

        assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            vehicleService.create(new CreateVehicleRequest("7FC90000000000002", "1.0.0"));
            throw new IllegalStateException("force rollback");
        }));

        assertEquals(1, outboxRepository.countByEventType("vehicle.registered"));
        assertEquals(1, vehicleService.getAll().size());
    }

    @Test
    void shouldPublishPendingEnvelopeAndMarkItPublished() {
        VehicleResponse vehicle = vehicleService.create(
                new CreateVehicleRequest("7FC90000000000003", "1.0.0")
        );
        OutboxEvent pending = outboxRepository.findAll().getFirst();
        assertNull(pending.getPublishedAt());

        publisher.publishPending();

        OutboxEvent published = outboxRepository.findById(pending.getId()).orElseThrow();
        assertNotNull(published.getPublishedAt());
        String kafkaValue = consumeOne(HeimdallTopics.VEHICLE_EVENTS);
        DomainEventEnvelope envelope = readEnvelope(kafkaValue);
        assertEquals("vehicle.registered", envelope.eventType());
        assertEquals(vehicle.id(), envelope.aggregateId());
        assertEquals(1, envelope.schemaVersion());
    }

    @Test
    void shouldCreateOneLogicalTerminalTransitionEventForDuplicateReport() {
        VehicleResponse vehicle = vehicleService.create(
                new CreateVehicleRequest("7FC90000000000004", "1.0.0")
        );
        SoftwareRelease release = releaseRepository.save(new SoftwareRelease(
                "2.0.0",
                "M9 test release",
                "/artifacts/2.0.0.bin",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        ));
        DeploymentResponse deployment = deploymentService.create(
                new CreateDeploymentRequest(vehicle.id(), release.getId())
        );

        transition(deployment.id(), DeploymentStatus.DOWNLOADING, null);
        transition(deployment.id(), DeploymentStatus.DOWNLOADED, null);
        transition(deployment.id(), DeploymentStatus.INSTALLING, null);
        transition(deployment.id(), DeploymentStatus.INSTALLED, null);
        transition(deployment.id(), DeploymentStatus.INSTALLED, null);

        assertEquals(4, outboxRepository.countByEventType("deployment.status.changed"));
        List<OutboxEvent> terminal = outboxRepository.findAll().stream()
                .filter(event -> event.getEventType().equals("deployment.status.changed"))
                .filter(event -> event.getPayload().contains("\"toStatus\":\"INSTALLED\""))
                .toList();
        assertEquals(1, terminal.size());
    }

    @Test
    void shouldRouteOnlyTerminalRolloutDeploymentEventsToEvaluator() throws Exception {
        UUID rolloutId = UUID.randomUUID();
        kafkaTemplate.send(
                HeimdallTopics.DEPLOYMENT_EVENTS,
                UUID.randomUUID().toString(),
                serializedDeploymentEvent("INSTALLING", rolloutId)
        ).get();

        Thread.sleep(500);
        verifyNoInteractions(rolloutEvaluator);

        String terminal = serializedDeploymentEvent("INSTALLED", rolloutId);
        kafkaTemplate.send(HeimdallTopics.DEPLOYMENT_EVENTS, UUID.randomUUID().toString(), terminal).get();
        kafkaTemplate.send(HeimdallTopics.DEPLOYMENT_EVENTS, UUID.randomUUID().toString(), terminal).get();

        verify(rolloutEvaluator, timeout(10_000).times(2)).evaluateRollout(rolloutId);
    }

    private void transition(UUID deploymentId, DeploymentStatus status, String failureReason) {
        deploymentService.updateStatus(
                deploymentId,
                new UpdateDeploymentStatusRequest(status, failureReason)
        );
    }

    private String serializedDeploymentEvent(String toStatus, UUID rolloutId) throws Exception {
        DomainEventEnvelope envelope = new DomainEventEnvelope(
                UUID.randomUUID(),
                "deployment.status.changed",
                1,
                "deployment",
                UUID.randomUUID(),
                Instant.now(),
                objectMapper.valueToTree(Map.of(
                        "deploymentId", UUID.randomUUID(),
                        "rolloutId", rolloutId,
                        "toStatus", toStatus
                ))
        );
        return objectMapper.writeValueAsString(envelope);
    }

    private DomainEventEnvelope readEnvelope(String value) {
        try {
            return objectMapper.readValue(value, DomainEventEnvelope.class);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String consumeOne(String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "m9-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(250));
                if (!records.isEmpty()) {
                    return records.iterator().next().value();
                }
            }
        }
        throw new AssertionError("No Kafka record received from " + topic);
    }

    @TestConfiguration
    static class ConsumerTestConfiguration {

        @Bean
        @Primary
        RolloutEvaluator testRolloutEvaluator() {
            return mock(RolloutEvaluator.class);
        }
    }
}
