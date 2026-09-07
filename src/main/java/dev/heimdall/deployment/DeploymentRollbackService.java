package dev.heimdall.deployment;

import dev.heimdall.release.SoftwareRelease;
import dev.heimdall.release.SoftwareReleaseRepository;
import dev.heimdall.vehicle.Vehicle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class DeploymentRollbackService {

    private final DeploymentRepository deploymentRepository;
    private final SoftwareReleaseRepository releaseRepository;
    private final DeploymentService deploymentService;

    public DeploymentRollbackService(
            DeploymentRepository deploymentRepository,
            SoftwareReleaseRepository releaseRepository,
            DeploymentService deploymentService
    ) {
        this.deploymentRepository = deploymentRepository;
        this.releaseRepository = releaseRepository;
        this.deploymentService = deploymentService;
    }

    @Transactional
    public DeploymentResponse rollback(UUID deploymentId) {
        Deployment sourceDeployment = deploymentRepository
                .findByIdForUpdate(deploymentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Deployment not found"
                ));

        return deploymentRepository.findByRollbackOfDeployment_Id(sourceDeployment.getId())
                .map(DeploymentResponse::from)
                .orElseGet(() -> createRollback(sourceDeployment));
    }

    private DeploymentResponse createRollback(Deployment sourceDeployment) {
        validateRollbackSource(sourceDeployment);

        SoftwareRelease rollbackRelease = releaseRepository
                .findByVersion(sourceDeployment.getSourceSoftwareVersion())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Rollback target software release is not available"
                ));

        Vehicle vehicle = sourceDeployment.getVehicle();
        if (!vehicle.getSoftwareVersion().equals(sourceDeployment.getRelease().getVersion())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Vehicle has moved since the deployment being rolled back"
            );
        }

        if (deploymentRepository.existsByVehicle_IdAndStatusIn(
                vehicle.getId(),
                DeploymentService.ACTIVE_STATUSES
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Vehicle already has an active deployment"
            );
        }

        return DeploymentResponse.from(deploymentService.createRollbackDeployment(
                vehicle,
                rollbackRelease,
                sourceDeployment
        ));
    }

    private void validateRollbackSource(Deployment sourceDeployment) {
        if (sourceDeployment.getRollbackOfDeployment() != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Rollback deployments cannot be rolled back"
            );
        }

        if (sourceDeployment.getStatus() != DeploymentStatus.INSTALLED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only installed deployments can be rolled back"
            );
        }
    }
}
