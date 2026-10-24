package dev.heimdall.vehicleauth;

import dev.heimdall.deployment.*;
import dev.heimdall.release.SoftwareRelease;
import dev.heimdall.release.SoftwareReleaseRepository;
import dev.heimdall.vehicle.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Testcontainers
class VehicleEnforcementIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");
    @Autowired RestTestClient client;
    @Autowired VehicleService vehicles;
    @Autowired VehicleTokenService tokens;
    @Autowired VehicleRepository vehicleRepository;
    @Autowired DeploymentService deployments;
    @Autowired DeploymentRepository deploymentRepository;
    @Autowired DeploymentEventRepository events;
    @Autowired SoftwareReleaseRepository releases;
    @Autowired MutableClock clock;
    static final AtomicInteger sequence = new AtomicInteger();
    static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary MutableClock authTestClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        volatile Instant now = NOW;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @BeforeEach void resetClock() { clock.now = NOW; }

    @Test void rejectsMissingMalformedWrongSchemeAmbiguousAndDuplicateHeadersBeforeMutation() {
        UUID id = provision().vehicle().id();
        String valid = token(id);
        for (String header : List.of("", "Basic abc", "Bearer", "Bearer ", "Bearer not-a-jwt",
                "Bearer " + valid + " extra", "Bearer\t" + valid, "Bearer  " + valid,
                "Bearer " + valid + ", Bearer " + valid)) {
            client.post().uri(heartbeat(id)).header("Authorization", header).exchange()
                    .expectStatus().isUnauthorized().expectBody().jsonPath("$.detail")
                    .isEqualTo("Vehicle authentication failed");
        }
        client.post().uri(heartbeat(id)).exchange().expectStatus().isUnauthorized();
        client.post().uri(heartbeat(id)).header("Authorization", "Bearer " + valid, "Bearer " + valid)
                .exchange().expectStatus().isUnauthorized();
        assertThat(vehicleRepository.findById(id).orElseThrow().getLastSeenAt()).isNull();
    }

    @Test void rejectsExpiredAndInvalidSignaturesOnEveryProtectedOperation() {
        UUID id = provision().vehicle().id();
        var deployment = deployment(id);
        String valid = token(id);
        var parts = valid.split("\\.");
        String invalid = parts[0] + "." + parts[1] + "." + (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        for (String value : List.of(invalid, valid)) {
            if (value.equals(valid)) clock.now = NOW.plusSeconds(300);
            client.post().uri(heartbeat(id)).header("Authorization", "Bearer " + value).exchange().expectStatus().isUnauthorized();
            client.get().uri(poll(id)).header("Authorization", "Bearer " + value).exchange().expectStatus().isUnauthorized();
            client.patch().uri(status(deployment.id())).header("Authorization", "Bearer " + value)
                    .body(Map.of("status", "DOWNLOADING")).exchange().expectStatus().isUnauthorized();
        }
        assertThat(vehicleRepository.findById(id).orElseThrow().getLastSeenAt()).isNull();
        assertThat(deployments.getById(deployment.id()).status()).isEqualTo(DeploymentStatus.PENDING);
        assertThat(events.findAllByDeployment_IdOrderByCreatedAtAsc(deployment.id())).hasSize(1);
    }

    @Test void ownHeartbeatWorksAndCrossVehicleHeartbeatCannotMutateAnyState() {
        var first = provision();
        var second = provision();
        String bearer = authenticate(first);
        client.post().uri(heartbeat(first.vehicle().id())).header("Authorization", bearer)
                .exchange().expectStatus().isOk();
        Vehicle before = vehicleRepository.findById(second.vehicle().id()).orElseThrow();
        client.post().uri(heartbeat(second.vehicle().id())).header("Authorization", bearer)
                .exchange().expectStatus().isForbidden();
        Vehicle after = vehicleRepository.findById(second.vehicle().id()).orElseThrow();
        assertThat(after.getLastSeenAt()).isEqualTo(before.getLastSeenAt()).isNull();
        assertThat(after.getConnectivityStatus()).isEqualTo(before.getConnectivityStatus());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    }

    @Test void pollingChecksIdentityWithAndWithoutAnActiveDeployment() {
        UUID first = provision().vehicle().id();
        UUID second = provision().vehicle().id();
        client.get().uri(poll(first)).exchange().expectStatus().isUnauthorized();
        client.get().uri(poll(first)).header("Authorization", "Bearer " + token(first)).exchange().expectStatus().isNoContent();
        for (int i = 0; i < 2; i++) {
            if (i == 1) deployment(second);
            client.get().uri(poll(second)).header("Authorization", "Bearer " + token(first))
                    .exchange().expectStatus().isForbidden().expectBody(String.class)
                    .value(body -> assertThat(body).doesNotContain("artifactUrl", "targetSoftwareVersion", "credential"));
        }
        client.get().uri(poll(second)).header("Authorization", "Bearer " + token(second))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.vehicleId").isEqualTo(second.toString());
    }

    @Test void statusOwnershipPrecedesIdempotenceStateMachineEventsAndSoftwareMutation() {
        UUID first = provision().vehicle().id();
        UUID second = provision().vehicle().id();
        var deployment = deployment(second);
        client.patch().uri(status(deployment.id())).body(Map.of("status", "DOWNLOADING"))
                .exchange().expectStatus().isUnauthorized();
        // Even an idempotent report and a terminal report must be forbidden for another vehicle.
        for (String next : List.of("PENDING", "DOWNLOADING", "INSTALLED")) {
            client.patch().uri(status(deployment.id())).header("Authorization", "Bearer " + token(first))
                    .body(Map.of("status", next)).exchange().expectStatus().isForbidden();
        }
        assertThat(deployments.getById(deployment.id()).status()).isEqualTo(DeploymentStatus.PENDING);
        assertThat(events.findAllByDeployment_IdOrderByCreatedAtAsc(deployment.id())).hasSize(1);
        assertThat(vehicles.getById(second).softwareVersion()).isEqualTo("1.0.0");
        for (String next : List.of("DOWNLOADING", "DOWNLOADED", "INSTALLING", "INSTALLED")) {
            client.patch().uri(status(deployment.id())).header("Authorization", "Bearer " + token(second))
                    .body(Map.of("status", next)).exchange().expectStatus().isOk();
        }
        assertThat(vehicles.getById(second).softwareVersion()).isEqualTo(deployment.targetSoftwareVersion());
        assertThat(events.findAllByDeployment_IdOrderByCreatedAtAsc(deployment.id())).hasSize(5);
    }

    @Test void authenticatedMissingResourcesReturn404WhileUnauthenticatedRequestsReturn401() {
        UUID missing = UUID.randomUUID();
        client.post().uri(heartbeat(missing)).header("Authorization", "Bearer " + token(missing)).exchange().expectStatus().isNotFound();
        client.get().uri(poll(missing)).header("Authorization", "Bearer " + token(missing)).exchange().expectStatus().isNotFound();
        client.patch().uri(status(missing)).header("Authorization", "Bearer " + token(missing))
                .body(Map.of("status", "DOWNLOADING")).exchange().expectStatus().isNotFound();
    }

    private VehicleProvisioningResponse provision() {
        return vehicles.create(new CreateVehicleRequest("7FC12" + String.format("%012d", sequence.incrementAndGet()), "1.0.0"));
    }
    private String authenticate(VehicleProvisioningResponse vehicle) {
        var response = client.post().uri("/api/v1/vehicle-auth/token")
                .body(new AuthenticateVehicleRequest(vehicle.vehicle().id(), vehicle.credential())).exchange()
                .expectStatus().isOk().expectBody(VehicleTokenResponse.class).returnResult().getResponseBody();
        return "Bearer " + response.accessToken();
    }
    private DeploymentResponse deployment(UUID id) {
        var release = releases.save(new SoftwareRelease("2.0." + sequence.incrementAndGet(), "auth test", "https://example.com/fw.bin", "abc"));
        return deployments.create(new CreateDeploymentRequest(id, release.getId()));
    }
    private String token(UUID id) { return tokens.issue(id).accessToken(); }
    private String heartbeat(UUID id) { return "/api/v1/vehicles/" + id + "/heartbeat"; }
    private String poll(UUID id) { return "/api/v1/vehicles/" + id + "/deployments/active"; }
    private String status(UUID id) { return "/api/v1/deployments/" + id + "/status"; }
}
