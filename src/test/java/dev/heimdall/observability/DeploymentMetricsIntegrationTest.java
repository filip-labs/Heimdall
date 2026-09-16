package dev.heimdall.observability;

import dev.heimdall.deployment.Deployment;
import dev.heimdall.deployment.DeploymentRepository;
import dev.heimdall.deployment.DeploymentService;
import dev.heimdall.deployment.DeploymentStatus;
import dev.heimdall.deployment.UpdateDeploymentStatusRequest;
import dev.heimdall.release.SoftwareRelease;
import dev.heimdall.release.SoftwareReleaseRepository;
import dev.heimdall.vehicle.Vehicle;
import dev.heimdall.vehicle.VehicleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
class DeploymentMetricsIntegrationTest {

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
    private DeploymentService deploymentService;

    @Test
    void shouldRecordEachCommittedTerminalTransitionOnlyOnce() {
        SoftwareRelease release = softwareReleaseRepository.saveAndFlush(
                new SoftwareRelease(
                        "2.0.0",
                        "Metrics test release",
                        "https://example.com/heimdall-2.0.0.bin",
                        "b".repeat(64)
                )
        );
        Deployment successful = createDeployment(
                "7FCOTA00000000001",
                release
        );
        Deployment failed = createDeployment(
                "7FCOTA00000000002",
                release
        );

        transition(successful, DeploymentStatus.DOWNLOADING, null);
        transition(successful, DeploymentStatus.DOWNLOADED, null);
        transition(successful, DeploymentStatus.INSTALLING, null);
        transition(successful, DeploymentStatus.INSTALLED, null);
        transition(successful, DeploymentStatus.INSTALLED, null);

        transition(failed, DeploymentStatus.FAILED, "simulated failure");
        transition(failed, DeploymentStatus.FAILED, "simulated failure");

        assertCounter("success", 1.0);
        assertCounter("failure", 1.0);
        assertTimer("success");
        assertTimer("failure");
    }

    private Deployment createDeployment(
            String vin,
            SoftwareRelease release
    ) {
        Vehicle vehicle = vehicleRepository.saveAndFlush(
                new Vehicle(vin, "1.0.0")
        );
        return deploymentRepository.saveAndFlush(
                new Deployment(vehicle, release)
        );
    }

    private void transition(
            Deployment deployment,
            DeploymentStatus status,
            String failureReason
    ) {
        deploymentService.updateStatus(
                deployment.getId(),
                new UpdateDeploymentStatusRequest(status, failureReason)
        );
    }

    private void assertCounter(String outcome, double expected) {
        assertEquals(
                expected,
                meterRegistry.get("heimdall.ota.outcomes")
                        .tag("outcome", outcome)
                        .counter()
                        .count()
        );
    }

    private void assertTimer(String outcome) {
        Timer timer = meterRegistry.get("heimdall.ota.duration")
                .tag("outcome", outcome)
                .timer();
        assertEquals(1L, timer.count());
        assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) >= 0);
    }
}
