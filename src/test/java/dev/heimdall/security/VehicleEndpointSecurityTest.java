package dev.heimdall.security;

import dev.heimdall.deployment.*;
import dev.heimdall.vehicle.VehicleController;
import dev.heimdall.vehicleauth.VehicleAuthenticated;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleEndpointSecurityTest {
    @Test void knownVehicleOperationsRequireAuthentication() throws NoSuchMethodException {
        for (var method : List.of(
                VehicleController.class.getDeclaredMethod("heartbeat", UUID.class, UUID.class),
                VehicleDeploymentController.class.getDeclaredMethod("getActiveDeployment", UUID.class, UUID.class),
                DeploymentController.class.getDeclaredMethod("updateStatus", UUID.class, UpdateDeploymentStatusRequest.class, UUID.class))) {
            assertThat(method.isAnnotationPresent(VehicleAuthenticated.class)).as(method.toGenericString()).isTrue();
        }
    }
}
