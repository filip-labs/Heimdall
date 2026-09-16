package dev.heimdall.observability;

import dev.heimdall.deployment.Deployment;
import dev.heimdall.deployment.DeploymentRepository;
import dev.heimdall.deployment.DeploymentStatus;
import dev.heimdall.release.SoftwareRelease;
import dev.heimdall.release.SoftwareReleaseRepository;
import dev.heimdall.rollout.Rollout;
import dev.heimdall.rollout.RolloutRepository;
import dev.heimdall.vehicle.Vehicle;
import dev.heimdall.vehicle.VehicleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Testcontainers
class HeimdallMetricsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17");

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private SoftwareReleaseRepository softwareReleaseRepository;

    @Autowired
    private DeploymentRepository deploymentRepository;

    @Autowired
    private RolloutRepository rolloutRepository;

    @Test
    void shouldExposeRepositoryBackedFleetAndControlPlaneGauges() {
        Vehicle onlineVehicle = new Vehicle("7FCMETRICS0000001", "1.0.0");
        onlineVehicle.heartbeat();
        Vehicle offlineVehicle = new Vehicle("7FCMETRICS0000002", "1.0.0");
        vehicleRepository.saveAndFlush(onlineVehicle);
        vehicleRepository.saveAndFlush(offlineVehicle);

        SoftwareRelease release = softwareReleaseRepository.saveAndFlush(
                new SoftwareRelease(
                        "1.1.0",
                        "Metrics test release",
                        "https://example.com/heimdall-1.1.0.bin",
                        "a".repeat(64)
                )
        );

        Deployment activeDeployment = new Deployment(onlineVehicle, release);
        Deployment failedDeployment = new Deployment(offlineVehicle, release);
        failedDeployment.transitionTo(DeploymentStatus.FAILED, "test failure");
        deploymentRepository.saveAndFlush(activeDeployment);
        deploymentRepository.saveAndFlush(failedDeployment);

        Rollout runningRollout = new Rollout(
                release,
                BigDecimal.valueOf(5),
                false,
                1
        );
        Rollout pausedRollout = new Rollout(
                release,
                BigDecimal.valueOf(5),
                false,
                1
        );
        pausedRollout.pause();
        rolloutRepository.saveAndFlush(runningRollout);
        rolloutRepository.saveAndFlush(pausedRollout);

        assertGauge("heimdall.vehicles", "online", 1.0);
        assertGauge("heimdall.vehicles", "offline", 1.0);
        assertGauge("heimdall.deployments", "active", 1.0);
        assertGauge("heimdall.deployments", "failed", 1.0);
        assertGauge("heimdall.rollouts", "running", 1.0);
        assertGauge("heimdall.rollouts", "paused", 1.0);
    }

    private void assertGauge(String name, String status, double expected) {
        assertEquals(
                expected,
                meterRegistry.get(name).tag("status", status).gauge().value()
        );
    }
}
