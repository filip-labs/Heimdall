package dev.heimdall.integration;

import dev.heimdall.deployment.DeploymentEventResponse;
import dev.heimdall.deployment.DeploymentResponse;
import dev.heimdall.deployment.DeploymentStatus;
import dev.heimdall.release.SoftwareReleaseResponse;
import dev.heimdall.vehicle.VehicleResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Testcontainers
class OtaFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17");

    @Autowired
    private RestTestClient restClient;

    @Test
    void shouldCompleteFullOtaFlow() {
        VehicleResponse vehicle = createVehicle(
                "7FC00000000000002",
                "1.0.0"
        );
        SoftwareReleaseResponse release = createRelease("1.1.0");
        DeploymentResponse deployment = createDeployment(vehicle.id(), release.id());

        assertEquals(
                DeploymentStatus.PENDING,
                deployment.status()
        );
        assertEquals("1.0.0", deployment.sourceSoftwareVersion());
        assertEventHistory(
                deployment.id(),
                new ExpectedEvent(null, DeploymentStatus.PENDING, null)
        );

        updateStatus(deployment.id(), "DOWNLOADING");
        updateStatus(deployment.id(), "DOWNLOADED");
        updateStatus(deployment.id(), "INSTALLING");
        updateStatus(deployment.id(), "INSTALLED");

        DeploymentResponse completedDeployment = restClient.get()
                .uri("/api/v1/deployments/" + deployment.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(DeploymentResponse.class)
                .returnResult()
                .getResponseBody();

        VehicleResponse updatedVehicle = restClient.get()
                .uri("/api/v1/vehicles/" + vehicle.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(VehicleResponse.class)
                .returnResult()
                .getResponseBody();

        assertEquals(
                "1.1.0",
                updatedVehicle.softwareVersion()
        );
        assertEquals("1.0.0", completedDeployment.sourceSoftwareVersion());
        assertEquals("1.1.0", completedDeployment.targetSoftwareVersion());
        assertEquals(DeploymentStatus.INSTALLED, completedDeployment.status());
        assertEventHistory(
                deployment.id(),
                new ExpectedEvent(null, DeploymentStatus.PENDING, null),
                new ExpectedEvent(
                        DeploymentStatus.PENDING,
                        DeploymentStatus.DOWNLOADING,
                        null
                ),
                new ExpectedEvent(
                        DeploymentStatus.DOWNLOADING,
                        DeploymentStatus.DOWNLOADED,
                        null
                ),
                new ExpectedEvent(
                        DeploymentStatus.DOWNLOADED,
                        DeploymentStatus.INSTALLING,
                        null
                ),
                new ExpectedEvent(
                        DeploymentStatus.INSTALLING,
                        DeploymentStatus.INSTALLED,
                        null
                )
        );
    }

    @Test
    void shouldTreatSameStatusUpdateAsIdempotent() {
        VehicleResponse vehicle = createVehicle(
                "7FC00000000000003",
                "1.0.0"
        );
        SoftwareReleaseResponse release = createRelease("1.1.1");
        DeploymentResponse deployment = createDeployment(vehicle.id(), release.id());

        DeploymentResponse firstUpdate = updateStatus(
                deployment.id(),
                "DOWNLOADING"
        );
        DeploymentResponse persistedUpdate = getDeployment(deployment.id());
        DeploymentResponse secondUpdate = updateStatus(
                deployment.id(),
                "DOWNLOADING"
        );

        assertEquals(DeploymentStatus.DOWNLOADING, firstUpdate.status());
        assertEquals(DeploymentStatus.DOWNLOADING, secondUpdate.status());
        assertEquals(persistedUpdate.updatedAt(), secondUpdate.updatedAt());
        assertEventHistory(
                deployment.id(),
                new ExpectedEvent(null, DeploymentStatus.PENDING, null),
                new ExpectedEvent(
                        DeploymentStatus.PENDING,
                        DeploymentStatus.DOWNLOADING,
                        null
                )
        );
    }

    @Test
    void shouldTreatInstalledStatusUpdateAsIdempotent() {
        VehicleResponse vehicle = createVehicle(
                "7FC00000000000004",
                "1.0.0"
        );
        SoftwareReleaseResponse release = createRelease("1.1.2");
        DeploymentResponse deployment = createDeployment(vehicle.id(), release.id());

        updateStatus(deployment.id(), "DOWNLOADING");
        updateStatus(deployment.id(), "DOWNLOADED");
        updateStatus(deployment.id(), "INSTALLING");
        updateStatus(deployment.id(), "INSTALLED");
        DeploymentResponse persistedInstalled = getDeployment(deployment.id());
        DeploymentResponse installedAgain = updateStatus(deployment.id(), "INSTALLED");

        assertEquals(DeploymentStatus.INSTALLED, installedAgain.status());
        assertEquals(persistedInstalled.updatedAt(), installedAgain.updatedAt());
        assertEventHistory(
                deployment.id(),
                new ExpectedEvent(null, DeploymentStatus.PENDING, null),
                new ExpectedEvent(
                        DeploymentStatus.PENDING,
                        DeploymentStatus.DOWNLOADING,
                        null
                ),
                new ExpectedEvent(
                        DeploymentStatus.DOWNLOADING,
                        DeploymentStatus.DOWNLOADED,
                        null
                ),
                new ExpectedEvent(
                        DeploymentStatus.DOWNLOADED,
                        DeploymentStatus.INSTALLING,
                        null
                ),
                new ExpectedEvent(
                        DeploymentStatus.INSTALLING,
                        DeploymentStatus.INSTALLED,
                        null
                )
        );
    }

    @Test
    void shouldTreatFailedStatusUpdateAsIdempotent() {
        VehicleResponse vehicle = createVehicle(
                "7FC00000000000005",
                "1.0.0"
        );
        SoftwareReleaseResponse release = createRelease("1.1.3");
        DeploymentResponse deployment = createDeployment(vehicle.id(), release.id());

        updateStatus(deployment.id(), "DOWNLOADING");
        updateStatus(
                deployment.id(),
                "FAILED",
                "Download timed out"
        );
        DeploymentResponse persistedFailed = getDeployment(deployment.id());
        DeploymentResponse failedAgain = updateStatus(
                deployment.id(),
                "FAILED",
                "Ignored retry reason"
        );

        assertEquals(DeploymentStatus.FAILED, failedAgain.status());
        assertEquals("Download timed out", failedAgain.failureReason());
        assertEquals(persistedFailed.updatedAt(), failedAgain.updatedAt());
        assertEventHistory(
                deployment.id(),
                new ExpectedEvent(null, DeploymentStatus.PENDING, null),
                new ExpectedEvent(
                        DeploymentStatus.PENDING,
                        DeploymentStatus.DOWNLOADING,
                        null
                ),
                new ExpectedEvent(
                        DeploymentStatus.DOWNLOADING,
                        DeploymentStatus.FAILED,
                        "Download timed out"
                )
        );
    }

    @Test
    void shouldRejectInvalidTransition() {
        VehicleResponse vehicle = createVehicle(
                "7FC00000000000006",
                "1.0.0"
        );
        SoftwareReleaseResponse release = createRelease("1.1.4");
        DeploymentResponse deployment = createDeployment(vehicle.id(), release.id());

        restClient.patch()
                .uri("/api/v1/deployments/" + deployment.id() + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", "INSTALLED"))
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void shouldExposeFailureEventHistory() {
        VehicleResponse vehicle = createVehicle(
                "7FC00000000000007",
                "1.0.0"
        );
        SoftwareReleaseResponse release = createRelease("1.1.5");
        DeploymentResponse deployment = createDeployment(vehicle.id(), release.id());

        updateStatus(deployment.id(), "DOWNLOADING");
        updateStatus(deployment.id(), "FAILED", "Checksum mismatch");

        assertEventHistory(
                deployment.id(),
                new ExpectedEvent(null, DeploymentStatus.PENDING, null),
                new ExpectedEvent(
                        DeploymentStatus.PENDING,
                        DeploymentStatus.DOWNLOADING,
                        null
                ),
                new ExpectedEvent(
                        DeploymentStatus.DOWNLOADING,
                        DeploymentStatus.FAILED,
                        "Checksum mismatch"
                )
        );
    }

    @Test
    void shouldReturnNotFoundForMissingDeploymentEvents() {
        restClient.get()
                .uri("/api/v1/deployments/" + UUID.randomUUID() + "/events")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void shouldGetVehicleByVin() {
        VehicleResponse vehicle = createVehicle(
                "7FC00000000000008",
                "1.3.0"
        );

        VehicleResponse resolvedVehicle = restClient.get()
                .uri("/api/v1/vehicles/by-vin/" + vehicle.vin())
                .exchange()
                .expectStatus().isOk()
                .expectBody(VehicleResponse.class)
                .returnResult()
                .getResponseBody();

        assertEquals(vehicle.id(), resolvedVehicle.id());
        assertEquals(vehicle.vin(), resolvedVehicle.vin());
        assertEquals(vehicle.softwareVersion(), resolvedVehicle.softwareVersion());
    }

    @Test
    void shouldReturnNotFoundForMissingVehicleVin() {
        restClient.get()
                .uri("/api/v1/vehicles/by-vin/7FC99999999999999")
                .exchange()
                .expectStatus().isNotFound();
    }

    private VehicleResponse createVehicle(
            String vin,
            String softwareVersion
    ) {
        return restClient.post()
                .uri("/api/v1/vehicles")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "vin", vin,
                        "softwareVersion", softwareVersion
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(VehicleResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private SoftwareReleaseResponse createRelease(String version) {
        return restClient.post()
                .uri("/api/v1/releases")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "version", version,
                        "description", "Integration test release",
                        "artifactUrl", "https://updates.heimdall.dev/" + version + ".bin",
                        "checksum", "abc123"
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(SoftwareReleaseResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private DeploymentResponse createDeployment(UUID vehicleId, UUID releaseId) {
        return restClient.post()
                .uri("/api/v1/deployments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "vehicleId", vehicleId,
                        "releaseId", releaseId
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(DeploymentResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private DeploymentResponse getDeployment(UUID deploymentId) {
        return restClient.get()
                .uri("/api/v1/deployments/" + deploymentId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DeploymentResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private DeploymentResponse updateStatus(UUID deploymentId, String status) {
        return updateStatus(deploymentId, status, null);
    }

    private DeploymentResponse updateStatus(
            UUID deploymentId,
            String status,
            String failureReason
    ) {
        Map<String, Object> body = failureReason == null
                ? Map.of("status", status)
                : Map.of(
                        "status", status,
                        "failureReason", failureReason
                );

        return restClient.patch()
                .uri("/api/v1/deployments/" + deploymentId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DeploymentResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private void assertEventHistory(
            UUID deploymentId,
            ExpectedEvent... expectedEvents
    ) {
        DeploymentEventResponse[] events = restClient.get()
                .uri("/api/v1/deployments/" + deploymentId + "/events")
                .exchange()
                .expectStatus().isOk()
                .expectBody(DeploymentEventResponse[].class)
                .returnResult()
                .getResponseBody();

        assertEquals(expectedEvents.length, events.length);

        Instant previousCreatedAt = null;

        for (int i = 0; i < expectedEvents.length; i++) {
            DeploymentEventResponse event = events[i];
            ExpectedEvent expectedEvent = expectedEvents[i];

            assertEquals(expectedEvent.fromStatus(), event.fromStatus());
            assertEquals(expectedEvent.toStatus(), event.toStatus());
            assertEquals(expectedEvent.failureReason(), event.failureReason());

            if (previousCreatedAt == null) {
                assertNull(event.fromStatus());
            } else {
                assertFalse(event.createdAt().isBefore(previousCreatedAt));
            }

            previousCreatedAt = event.createdAt();
        }
    }

    private record ExpectedEvent(
            DeploymentStatus fromStatus,
            DeploymentStatus toStatus,
            String failureReason
    ) {
    }
}