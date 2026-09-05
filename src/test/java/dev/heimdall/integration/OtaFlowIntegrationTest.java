package dev.heimdall.integration;

import dev.heimdall.deployment.DeploymentResponse;
import dev.heimdall.deployment.DeploymentStatus;
import dev.heimdall.vehicle.SoftwareReleaseResponse;
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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        VehicleResponse vehicle = restClient.post()
                .uri("/api/v1/vehicles")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "vin", "7FC00000000000002",
                        "softwareVersion", "1.0.0"
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(VehicleResponse.class)
                .returnResult()
                .getResponseBody();

        SoftwareReleaseResponse release = restClient.post()
                .uri("/api/v1/releases")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "version", "1.1.0",
                        "description", "Integration test release",
                        "artifactUrl", "https://updates.heimdall.dev/1.1.0.bin",
                        "checksum", "abc123"
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(SoftwareReleaseResponse.class)
                .returnResult()
                .getResponseBody();

        DeploymentResponse deployment = restClient.post()
                .uri("/api/v1/deployments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "vehicleId", vehicle.id(),
                        "releaseId", release.id()
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(DeploymentResponse.class)
                .returnResult()
                .getResponseBody();

        assertEquals(
                DeploymentStatus.PENDING,
                deployment.status()
        );

        updateStatus(deployment.id(), "DOWNLOADING");
        updateStatus(deployment.id(), "DOWNLOADED");
        updateStatus(deployment.id(), "INSTALLING");
        updateStatus(deployment.id(), "INSTALLED");

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
    }

    private void updateStatus(java.util.UUID deploymentId, String status) {
        restClient.patch()
                .uri("/api/v1/deployments/" + deploymentId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", status))
                .exchange()
                .expectStatus().isOk();
    }
}
