package dev.heimdall.deployment;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vehicles/{vehicleId}/deployments")
public class VehicleDeploymentController {

    private final DeploymentService deploymentService;

    public VehicleDeploymentController(
            DeploymentService deploymentService
    ) {
        this.deploymentService = deploymentService;
    }

    @GetMapping("/active")
    public ResponseEntity<DeploymentResponse> getActiveDeployment(
            @PathVariable UUID vehicleId
    ) {
        return deploymentService
                .getActiveForVehicle(vehicleId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
