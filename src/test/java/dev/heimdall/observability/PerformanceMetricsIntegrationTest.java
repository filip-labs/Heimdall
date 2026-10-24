package dev.heimdall.observability;

import dev.heimdall.deployment.CreateDeploymentRequest;
import dev.heimdall.deployment.DeploymentService;
import dev.heimdall.release.CreateSoftwareReleaseRequest;
import dev.heimdall.release.SoftwareReleaseResponse;
import dev.heimdall.release.SoftwareReleaseService;
import dev.heimdall.vehicle.CreateVehicleRequest;
import dev.heimdall.vehicle.VehicleResponse;
import dev.heimdall.vehicle.VehicleService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMetrics
@Testcontainers
class PerformanceMetricsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired VehicleService vehicleService;
    @Autowired SoftwareReleaseService releaseService;
    @Autowired DeploymentService deploymentService;
    @Autowired MeterRegistry meterRegistry;

    @Test
    void shouldCountCommittedDeploymentCreation() {
        VehicleResponse vehicle = vehicleService.create(
                new CreateVehicleRequest("7K600000000000001", "1.0.0")).vehicle();
        SoftwareReleaseResponse release = releaseService.create(
                new CreateSoftwareReleaseRequest(
                        "2.0.0",
                        "Load-test metric fixture",
                        "http://localhost/artifact.bin",
                        "a".repeat(64)));

        deploymentService.create(new CreateDeploymentRequest(vehicle.id(), release.id()));

        assertThat(meterRegistry.get("heimdall.deployments.created").counter().count())
                .isEqualTo(1.0);
    }
}
