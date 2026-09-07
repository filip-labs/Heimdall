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
import java.util.Arrays;
import java.util.List;
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

    @Test
    void shouldCreateRollbackDeploymentToOriginalSourceVersion() {
        SoftwareReleaseResponse previousRelease = createRelease("2.0.1");
        SoftwareReleaseResponse targetRelease = createRelease("2.1.1");
        VehicleResponse vehicle = createVehicle("7FC00000000000009", "2.0.1");
        DeploymentResponse original = createDeployment(vehicle.id(), targetRelease.id());
        installDeployment(original.id());

        DeploymentResponse rollback = rollbackDeployment(original.id());

        assertEquals(vehicle.id(), rollback.vehicleId());
        assertEquals(previousRelease.id(), rollback.releaseId());
        assertEquals("2.1.1", rollback.sourceSoftwareVersion());
        assertEquals("2.0.1", rollback.targetSoftwareVersion());
        assertEquals(DeploymentStatus.PENDING, rollback.status());
        assertEquals(original.id(), rollback.rollbackOfDeploymentId());
    }

    @Test
    void shouldInstallRollbackThroughNormalOtaFlow() {
        SoftwareReleaseResponse previousRelease = createRelease("2.0.2");
        SoftwareReleaseResponse targetRelease = createRelease("2.1.2");
        VehicleResponse vehicle = createVehicle("7FC00000000000010", "2.0.2");
        DeploymentResponse original = createDeployment(vehicle.id(), targetRelease.id());
        installDeployment(original.id());

        DeploymentResponse rollback = rollbackDeployment(original.id());
        installDeployment(rollback.id());

        VehicleResponse rolledBackVehicle = getVehicle(vehicle.id());
        DeploymentResponse installedRollback = getDeployment(rollback.id());

        assertEquals(previousRelease.id(), installedRollback.releaseId());
        assertEquals("2.0.2", rolledBackVehicle.softwareVersion());
        assertEquals(DeploymentStatus.INSTALLED, installedRollback.status());
        assertEquals(original.id(), installedRollback.rollbackOfDeploymentId());
    }

    @Test
    void shouldKeepSeparateRollbackEventHistoryAndOriginalHistoryImmutable() {
        SoftwareReleaseResponse previousRelease = createRelease("2.0.3");
        SoftwareReleaseResponse targetRelease = createRelease("2.1.3");
        VehicleResponse vehicle = createVehicle("7FC00000000000011", "2.0.3");
        DeploymentResponse original = createDeployment(vehicle.id(), targetRelease.id());
        installDeployment(original.id());
        DeploymentResponse originalBeforeRollback = getDeployment(original.id());
        List<DeploymentEventResponse> originalEventsBeforeRollback =
                getDeploymentEvents(original.id());

        DeploymentResponse rollback = rollbackDeployment(original.id());
        installDeployment(rollback.id());

        DeploymentResponse originalAfterRollback = getDeployment(original.id());
        List<DeploymentEventResponse> originalEventsAfterRollback =
                getDeploymentEvents(original.id());

        assertEquals(previousRelease.id(), rollback.releaseId());
        assertEquals(originalBeforeRollback.status(), originalAfterRollback.status());
        assertEquals(originalBeforeRollback.failureReason(), originalAfterRollback.failureReason());
        assertEquals(
                originalBeforeRollback.sourceSoftwareVersion(),
                originalAfterRollback.sourceSoftwareVersion()
        );
        assertEquals(originalBeforeRollback.releaseId(), originalAfterRollback.releaseId());
        assertEquals(originalBeforeRollback.createdAt(), originalAfterRollback.createdAt());
        assertEquals(originalEventsBeforeRollback.size(), originalEventsAfterRollback.size());
        assertEventHistory(
                rollback.id(),
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
    void shouldLeaveVehicleVersionUnchangedWhenRollbackFails() {
        createRelease("2.0.4");
        SoftwareReleaseResponse targetRelease = createRelease("2.1.4");
        VehicleResponse vehicle = createVehicle("7FC00000000000012", "2.0.4");
        DeploymentResponse original = createDeployment(vehicle.id(), targetRelease.id());
        installDeployment(original.id());

        DeploymentResponse rollback = rollbackDeployment(original.id());
        updateStatus(rollback.id(), "DOWNLOADING");
        updateStatus(rollback.id(), "FAILED", "Rollback failed");

        assertEquals("2.1.4", getVehicle(vehicle.id()).softwareVersion());
        assertEquals(DeploymentStatus.FAILED, getDeployment(rollback.id()).status());
        assertEquals(DeploymentStatus.INSTALLED, getDeployment(original.id()).status());
    }

    @Test
    void shouldRejectRollbackWhenSourceDeploymentIsNotInstalled() {
        createRelease("2.0.5");
        SoftwareReleaseResponse targetRelease = createRelease("2.1.5");
        VehicleResponse vehicle = createVehicle("7FC00000000000013", "2.0.5");
        DeploymentResponse deployment = createDeployment(vehicle.id(), targetRelease.id());

        assertRollbackConflict(deployment.id());
        assertEquals(1, deploymentsForVehicle(vehicle.id()).size());
    }

    @Test
    void shouldRejectRollbackWhenPreviousReleaseIsMissing() {
        SoftwareReleaseResponse targetRelease = createRelease("2.1.6");
        VehicleResponse vehicle = createVehicle("7FC00000000000014", "2.0.6");
        DeploymentResponse original = createDeployment(vehicle.id(), targetRelease.id());
        installDeployment(original.id());

        assertRollbackConflict(original.id());

        assertEquals(1, deploymentsForVehicle(vehicle.id()).size());
    }

    @Test
    void shouldRejectRollbackWhenVehicleHasMovedSinceSourceDeployment() {
        createRelease("2.0.7");
        SoftwareReleaseResponse firstTargetRelease = createRelease("2.1.7");
        SoftwareReleaseResponse secondTargetRelease = createRelease("2.2.7");
        VehicleResponse vehicle = createVehicle("7FC00000000000015", "2.0.7");
        DeploymentResponse original = createDeployment(vehicle.id(), firstTargetRelease.id());
        installDeployment(original.id());
        DeploymentResponse newerDeployment = createDeployment(vehicle.id(), secondTargetRelease.id());
        installDeployment(newerDeployment.id());

        assertRollbackConflict(original.id());

        assertEquals(2, deploymentsForVehicle(vehicle.id()).size());
        assertEquals("2.2.7", getVehicle(vehicle.id()).softwareVersion());
    }

    @Test
    void shouldRejectRollbackWhenAnotherActiveDeploymentExists() {
        createRelease("2.0.8");
        SoftwareReleaseResponse targetRelease = createRelease("2.1.8");
        SoftwareReleaseResponse activeRelease = createRelease("2.2.8");
        VehicleResponse vehicle = createVehicle("7FC00000000000016", "2.0.8");
        DeploymentResponse original = createDeployment(vehicle.id(), targetRelease.id());
        installDeployment(original.id());
        createDeployment(vehicle.id(), activeRelease.id());

        assertRollbackConflict(original.id());

        assertEquals(2, deploymentsForVehicle(vehicle.id()).size());
        assertEquals("2.1.8", getVehicle(vehicle.id()).softwareVersion());
    }

    @Test
    void shouldReturnExistingRollbackForRepeatedRequests() {
        createRelease("2.0.9");
        SoftwareReleaseResponse targetRelease = createRelease("2.1.9");
        VehicleResponse vehicle = createVehicle("7FC00000000000017", "2.0.9");
        DeploymentResponse original = createDeployment(vehicle.id(), targetRelease.id());
        installDeployment(original.id());

        DeploymentResponse firstRollback = rollbackDeployment(original.id());
        DeploymentResponse secondRollback = rollbackDeployment(original.id());

        assertEquals(firstRollback.id(), secondRollback.id());
        assertEquals(2, deploymentsForVehicle(vehicle.id()).size());

        updateStatus(firstRollback.id(), "DOWNLOADING");
        updateStatus(firstRollback.id(), "FAILED", "Rollback failed");
        DeploymentResponse thirdRollback = rollbackDeployment(original.id());

        assertEquals(firstRollback.id(), thirdRollback.id());
        assertEquals(2, deploymentsForVehicle(vehicle.id()).size());
    }

    @Test
    void shouldRejectRollbackOfRollback() {
        createRelease("2.0.10");
        SoftwareReleaseResponse targetRelease = createRelease("2.1.10");
        VehicleResponse vehicle = createVehicle("7FC00000000000018", "2.0.10");
        DeploymentResponse original = createDeployment(vehicle.id(), targetRelease.id());
        installDeployment(original.id());
        DeploymentResponse rollback = rollbackDeployment(original.id());
        installDeployment(rollback.id());

        assertRollbackConflict(rollback.id());

        assertEquals(2, deploymentsForVehicle(vehicle.id()).size());
    }

    @Test
    void shouldReturnNotFoundWhenRollbackSourceDeploymentIsMissing() {
        restClient.post()
                .uri("/api/v1/deployments/" + UUID.randomUUID() + "/rollback")
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

    private VehicleResponse getVehicle(UUID vehicleId) {
        return restClient.get()
                .uri("/api/v1/vehicles/" + vehicleId)
                .exchange()
                .expectStatus().isOk()
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

    private DeploymentResponse rollbackDeployment(UUID deploymentId) {
        return restClient.post()
                .uri("/api/v1/deployments/" + deploymentId + "/rollback")
                .exchange()
                .expectStatus().isOk()
                .expectBody(DeploymentResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private void assertRollbackConflict(UUID deploymentId) {
        restClient.post()
                .uri("/api/v1/deployments/" + deploymentId + "/rollback")
                .exchange()
                .expectStatus().isEqualTo(409);
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

    private List<DeploymentResponse> deploymentsForVehicle(UUID vehicleId) {
        DeploymentResponse[] deployments = restClient.get()
                .uri("/api/v1/deployments")
                .exchange()
                .expectStatus().isOk()
                .expectBody(DeploymentResponse[].class)
                .returnResult()
                .getResponseBody();

        return Arrays.stream(deployments)
                .filter(deployment -> deployment.vehicleId().equals(vehicleId))
                .toList();
    }

    private void installDeployment(UUID deploymentId) {
        updateStatus(deploymentId, "DOWNLOADING");
        updateStatus(deploymentId, "DOWNLOADED");
        updateStatus(deploymentId, "INSTALLING");
        updateStatus(deploymentId, "INSTALLED");
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
        List<DeploymentEventResponse> events = getDeploymentEvents(deploymentId);

        assertEquals(expectedEvents.length, events.size());

        Instant previousCreatedAt = null;

        for (int i = 0; i < expectedEvents.length; i++) {
            DeploymentEventResponse event = events.get(i);
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

    private List<DeploymentEventResponse> getDeploymentEvents(UUID deploymentId) {
        DeploymentEventResponse[] events = restClient.get()
                .uri("/api/v1/deployments/" + deploymentId + "/events")
                .exchange()
                .expectStatus().isOk()
                .expectBody(DeploymentEventResponse[].class)
                .returnResult()
                .getResponseBody();

        return Arrays.asList(events);
    }

    private record ExpectedEvent(
            DeploymentStatus fromStatus,
            DeploymentStatus toStatus,
            String failureReason
    ) {
    }
}
