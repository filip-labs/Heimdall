package dev.heimdall.deployment;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/deployments")
public class DeploymentController {

    private final DeploymentService deploymentService;
    private final DeploymentRollbackService deploymentRollbackService;

    public DeploymentController(
            DeploymentService deploymentService,
            DeploymentRollbackService deploymentRollbackService
    ) {
        this.deploymentService = deploymentService;
        this.deploymentRollbackService = deploymentRollbackService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeploymentResponse create(
            @Valid @RequestBody CreateDeploymentRequest request
    ) {
        return deploymentService.create(request);
    }

    @GetMapping("/{id}")
    public DeploymentResponse getById(@PathVariable UUID id) {
        return deploymentService.getById(id);
    }

    @GetMapping("/{id}/events")
    public List<DeploymentEventResponse> getEvents(@PathVariable UUID id) {
        return deploymentService.getEvents(id);
    }

    @GetMapping
    public List<DeploymentResponse> getAll() {
        return deploymentService.getAll();
    }

    @PatchMapping("/{id}/status")
    public DeploymentResponse updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDeploymentStatusRequest request
    ) {
        return deploymentService.updateStatus(id, request);
    }

    @PostMapping("/{id}/rollback")
    public DeploymentResponse rollback(@PathVariable UUID id) {
        return deploymentRollbackService.rollback(id);
    }
}
