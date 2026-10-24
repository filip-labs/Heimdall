package dev.heimdall.security;

import dev.heimdall.deployment.*;
import dev.heimdall.release.SoftwareReleaseResponse;
import dev.heimdall.vehicle.*;
import dev.heimdall.vehicleauth.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP provisioning, credential exchange and ownership checks against real PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class SecurityRegressionIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");
    static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    static final AtomicInteger sequence = new AtomicInteger();
    @Autowired RestTestClient client;
    @Autowired MutableClock clock;

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary MutableClock securityRegressionClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        volatile Instant now = NOW;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @BeforeEach void resetClock() { clock.now = NOW; }

    @Test void missingForgedAndExpiredTokensRejectEveryVehicleOperationWithoutMutation() {
        UUID vehicle = provision();
        var deployment = deploy(vehicle);
        var before = snapshot(vehicle, deployment.id());
        String valid = authenticate(vehicle);
        String[] parts = valid.split("\\.");
        String forged = parts[0] + "." + parts[1] + "."
                + (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        rejectOperations(vehicle, deployment.id(), "", 401);
        rejectOperations(vehicle, deployment.id(), "Bearer " + forged, 401);
        clock.now = NOW.plusSeconds(300); // Exact expiration boundary, without sleeping.
        rejectOperations(vehicle, deployment.id(), "Bearer " + valid, 401);
        assertThat(snapshot(vehicle, deployment.id())).isEqualTo(before);
    }

    @Test void oneVehicleCannotHeartbeatPollOrReportAnotherVehiclesDeployment() {
        UUID first = provision();
        UUID second = provision();
        var deployment = deploy(second);
        var before = snapshot(second, deployment.id());
        rejectOperations(second, deployment.id(), "Bearer " + authenticate(first), 403);
        assertThat(snapshot(second, deployment.id())).isEqualTo(before);
    }

    @Test void authenticatedOwnerReceivesSignedMetadataAndCompletesAllowedTransitions() {
        UUID vehicle = provision();
        var deployment = deploy(vehicle);
        String bearer = "Bearer " + authenticate(vehicle);
        client.post().uri(heartbeat(vehicle)).header("Authorization", bearer).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.lastSeenAt").isNotEmpty();
        var polled = client.get().uri(poll(vehicle)).header("Authorization", bearer).exchange()
                .expectStatus().isOk().expectBody(DeploymentResponse.class).returnResult().getResponseBody();
        assertThat(polled.id()).isEqualTo(deployment.id());
        assertThat(polled.vehicleId()).isEqualTo(deployment.vehicleId());
        assertThat(polled.vin()).isEqualTo(deployment.vin());
        assertThat(polled.sourceSoftwareVersion()).isEqualTo(deployment.sourceSoftwareVersion());
        assertThat(polled.releaseId()).isEqualTo(deployment.releaseId());
        assertThat(polled.targetSoftwareVersion()).isEqualTo(deployment.targetSoftwareVersion());
        assertThat(polled.artifactUrl()).isEqualTo(deployment.artifactUrl());
        assertThat(polled.checksum()).isEqualTo(deployment.checksum());
        assertThat(polled.signatureAlgorithm()).isEqualTo("Ed25519");
        assertThat(polled.signatureAlgorithm()).isEqualTo(deployment.signatureAlgorithm());
        assertThat(polled.signingKeyId()).isEqualTo(deployment.signingKeyId());
        assertThat(polled.signature()).isEqualTo(deployment.signature());
        assertThat(polled.signature()).matches("[A-Za-z0-9_-]{86}");
        assertThat(polled.status()).isEqualTo(DeploymentStatus.PENDING);
        assertThat(polled.failureReason()).isNull();
        assertThat(polled.rollbackOfDeploymentId()).isNull();
        for (String next : List.of("DOWNLOADING", "DOWNLOADED", "INSTALLING", "INSTALLED")) {
            client.patch().uri(status(deployment.id())).header("Authorization", bearer)
                    .body(Map.of("status", next)).exchange().expectStatus().isOk();
        }
        var after = snapshot(vehicle, deployment.id());
        assertThat(after.vehicle().softwareVersion()).isEqualTo(deployment.targetSoftwareVersion());
        assertThat(after.deployment().status()).isEqualTo(DeploymentStatus.INSTALLED);
        assertThat(after.events()).extracting(DeploymentEventResponse::toStatus).containsExactly(
                DeploymentStatus.PENDING, DeploymentStatus.DOWNLOADING, DeploymentStatus.DOWNLOADED,
                DeploymentStatus.INSTALLING, DeploymentStatus.INSTALLED);
    }

    // Fixture credentials stay in memory; no secret fixture files are written.
    private final Map<UUID, String> credentials = new HashMap<>();
    private UUID provision() {
        var response = client.post().uri("/api/v1/vehicles")
                .body(Map.of("vin", "7FC99" + String.format("%012d", sequence.incrementAndGet()), "softwareVersion", "1.0.0"))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        UUID id = UUID.fromString((String) response.get("id"));
        credentials.put(id, (String) response.get("credential"));
        return id;
    }
    private String authenticate(UUID vehicle) {
        return client.post().uri("/api/v1/vehicle-auth/token")
                .body(new AuthenticateVehicleRequest(vehicle, credentials.get(vehicle)))
                .exchange().expectStatus().isOk().expectBody(VehicleTokenResponse.class)
                .returnResult().getResponseBody().accessToken();
    }
    private DeploymentResponse deploy(UUID vehicle) {
        var release = client.post().uri("/api/v1/releases")
                .body(Map.of("version", "security-" + sequence.incrementAndGet(), "artifactUrl", "https://example.com/fw",
                        "checksum", "ab".repeat(32)))
                .exchange().expectStatus().isCreated().expectBody(SoftwareReleaseResponse.class).returnResult().getResponseBody();
        return client.post().uri("/api/v1/deployments").body(new CreateDeploymentRequest(vehicle, release.id()))
                .exchange().expectStatus().isCreated().expectBody(DeploymentResponse.class).returnResult().getResponseBody();
    }
    private void rejectOperations(UUID vehicle, UUID deployment, String bearer, int expected) {
        var heartbeat = client.post().uri(heartbeat(vehicle));
        var poll = client.get().uri(poll(vehicle));
        var status = client.patch().uri(status(deployment));
        if (!bearer.isEmpty()) {
            heartbeat.header("Authorization", bearer);
            poll.header("Authorization", bearer);
            status.header("Authorization", bearer);
        }
        heartbeat.exchange().expectStatus().isEqualTo(expected);
        poll.exchange().expectStatus().isEqualTo(expected);
        // Terminal report would change software version if authorization were bypassed.
        status.body(Map.of("status", "INSTALLED")).exchange().expectStatus().isEqualTo(expected);
        // Also exercise an otherwise allowed transition, so a state-machine rejection cannot mask the guard.
        var allowed = client.patch().uri(status(deployment));
        if (!bearer.isEmpty()) allowed.header("Authorization", bearer);
        allowed.body(Map.of("status", "DOWNLOADING")).exchange().expectStatus().isEqualTo(expected);
    }
    private Snapshot snapshot(UUID vehicle, UUID deployment) {
        return new Snapshot(client.get().uri("/api/v1/vehicles/" + vehicle).exchange().expectStatus().isOk()
                .expectBody(VehicleResponse.class).returnResult().getResponseBody(),
                client.get().uri("/api/v1/deployments/" + deployment).exchange().expectStatus().isOk()
                        .expectBody(DeploymentResponse.class).returnResult().getResponseBody(),
                Arrays.asList(client.get().uri("/api/v1/deployments/" + deployment + "/events").exchange().expectStatus().isOk()
                        .expectBody(DeploymentEventResponse[].class).returnResult().getResponseBody()));
    }
    private record Snapshot(VehicleResponse vehicle, DeploymentResponse deployment, List<DeploymentEventResponse> events) {}
    private String heartbeat(UUID id) { return "/api/v1/vehicles/" + id + "/heartbeat"; }
    private String poll(UUID id) { return "/api/v1/vehicles/" + id + "/deployments/active"; }
    private String status(UUID id) { return "/api/v1/deployments/" + id + "/status"; }
}
