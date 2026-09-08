package dev.heimdall.deployment;

import dev.heimdall.api.ConflictException;
import dev.heimdall.api.ResourceNotFoundException;
import dev.heimdall.release.SoftwareRelease;
import dev.heimdall.release.SoftwareReleaseRepository;
import dev.heimdall.vehicle.Vehicle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeploymentRollbackService {

    private final DeploymentRepository deploymentRepository;
    private final SoftwareReleaseRepository releaseRepository;
    private final DeploymentService deploymentService;

    @Transactional
    public DeploymentResponse rollback(UUID deploymentId) {
        Deployment sourceDeployment = deploymentRepository
                .findByIdForUpdate(deploymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Deployment not found"));

        return deploymentRepository.findByRollbackOfDeployment_Id(sourceDeployment.getId())
                .map(DeploymentResponse::from)
                .orElseGet(() -> createRollback(sourceDeployment));
    }

    private DeploymentResponse createRollback(Deployment sourceDeployment) {
        validateRollbackSource(sourceDeployment);

        SoftwareRelease rollbackRelease = releaseRepository
                .findByVersion(sourceDeployment.getSourceSoftwareVersion())
                .orElseThrow(() -> new ConflictException(
                        "Rollback target software release is not available"
                ));

        Vehicle vehicle = sourceDeployment.getVehicle();
        if (!vehicle.getSoftwareVersion().equals(sourceDeployment.getRelease().getVersion())) {
            throw new ConflictException(
                    "Vehicle has moved since the deployment being rolled back"
            );
        }

        if (deploymentRepository.existsByVehicle_IdAndStatusIn(
                vehicle.getId(),
                DeploymentService.ACTIVE_STATUSES
        )) {
            throw new ConflictException("Vehicle already has an active deployment");
        }

        return DeploymentResponse.from(deploymentService.createRollbackDeployment(
                vehicle,
                rollbackRelease,
                sourceDeployment
        ));
    }

    private void validateRollbackSource(Deployment sourceDeployment) {
        if (sourceDeployment.getRollbackOfDeployment() != null) {
            throw new ConflictException("Rollback deployments cannot be rolled back");
        }

        if (sourceDeployment.getStatus() != DeploymentStatus.INSTALLED) {
            throw new ConflictException("Only installed deployments can be rolled back");
        }
    }
}
