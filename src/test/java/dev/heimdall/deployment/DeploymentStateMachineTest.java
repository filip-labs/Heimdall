package dev.heimdall.deployment;

import dev.heimdall.vehicle.SoftwareRelease;
import dev.heimdall.vehicle.Vehicle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentStateMachineTest {

    private Deployment createDeployment() {
        Vehicle vehicle = new Vehicle(
                "7FC00000000000001",
                "1.0.0"
        );

        SoftwareRelease release = new SoftwareRelease(
                "1.1.0",
                "Test release",
                "https://updates.heimdall.dev/1.1.0.bin",
                "abc123"
        );

        return new Deployment(vehicle, release);
    }

    @Test
    void shouldCompleteValidDeploymentLifecycle() {
        Deployment deployment = createDeployment();

        assertEquals(DeploymentStatus.PENDING, deployment.getStatus());

        deployment.transitionTo(DeploymentStatus.DOWNLOADING, null);
        assertEquals(DeploymentStatus.DOWNLOADING, deployment.getStatus());

        deployment.transitionTo(DeploymentStatus.DOWNLOADED, null);
        assertEquals(DeploymentStatus.DOWNLOADED, deployment.getStatus());

        deployment.transitionTo(DeploymentStatus.INSTALLING, null);
        assertEquals(DeploymentStatus.INSTALLING, deployment.getStatus());

        deployment.transitionTo(DeploymentStatus.INSTALLED, null);
        assertEquals(DeploymentStatus.INSTALLED, deployment.getStatus());
    }

    @Test
    void shouldRejectSkippingDeploymentStates() {
        Deployment deployment = createDeployment();

        assertThrows(
                IllegalStateException.class,
                () -> deployment.transitionTo(
                        DeploymentStatus.INSTALLED,
                        null
                )
        );

        assertEquals(DeploymentStatus.PENDING, deployment.getStatus());
    }

    @Test
    void shouldAllowDeploymentToFail() {
        Deployment deployment = createDeployment();

        deployment.transitionTo(DeploymentStatus.DOWNLOADING, null);

        deployment.transitionTo(
                DeploymentStatus.FAILED,
                "Download timed out"
        );

        assertEquals(DeploymentStatus.FAILED, deployment.getStatus());
        assertEquals(
                "Download timed out",
                deployment.getFailureReason()
        );
    }

    @Test
    void shouldRejectFailureWithoutReason() {
        Deployment deployment = createDeployment();

        assertThrows(
                IllegalArgumentException.class,
                () -> deployment.transitionTo(
                        DeploymentStatus.FAILED,
                        null
                )
        );
    }

    @Test
    void shouldNotAllowTransitionAfterInstallation() {
        Deployment deployment = createDeployment();

        deployment.transitionTo(DeploymentStatus.DOWNLOADING, null);
        deployment.transitionTo(DeploymentStatus.DOWNLOADED, null);
        deployment.transitionTo(DeploymentStatus.INSTALLING, null);
        deployment.transitionTo(DeploymentStatus.INSTALLED, null);

        assertThrows(
                IllegalStateException.class,
                () -> deployment.transitionTo(
                        DeploymentStatus.FAILED,
                        "Too late"
                )
        );
    }

    @Test
    void shouldNotAllowTransitionAfterFailure() {
        Deployment deployment = createDeployment();

        deployment.transitionTo(
                DeploymentStatus.FAILED,
                "Vehicle disconnected"
        );

        assertThrows(
                IllegalStateException.class,
                () -> deployment.transitionTo(
                        DeploymentStatus.DOWNLOADING,
                        null
                )
        );
    }
}
