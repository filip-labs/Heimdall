package dev.heimdall.deployment;

import dev.heimdall.vehicle.SoftwareRelease;
import dev.heimdall.vehicle.SoftwareReleaseRepository;
import dev.heimdall.vehicle.Vehicle;
import dev.heimdall.vehicle.VehicleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class DeploymentService {

    private final DeploymentRepository deploymentRepository;
    private final VehicleRepository vehicleRepository;
    private final SoftwareReleaseRepository releaseRepository;

    public DeploymentService(
            DeploymentRepository deploymentRepository,
            VehicleRepository vehicleRepository,
            SoftwareReleaseRepository releaseRepository
    ) {
        this.deploymentRepository = deploymentRepository;
        this.vehicleRepository = vehicleRepository;
        this.releaseRepository = releaseRepository;
    }

    @Transactional
    public DeploymentResponse create(CreateDeploymentRequest request) {
        Vehicle vehicle = vehicleRepository.findById(request.vehicleId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Vehicle not found"
                ));

        SoftwareRelease release = releaseRepository.findById(request.releaseId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Software release not found"
                ));

        if (vehicle.getSoftwareVersion().equals(release.getVersion())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Vehicle is already running this software version"
            );
        }

        Deployment deployment = new Deployment(vehicle, release);

        return DeploymentResponse.from(
                deploymentRepository.save(deployment)
        );
    }

    @Transactional
    public DeploymentResponse updateStatus(
            UUID deploymentId,
            UpdateDeploymentStatusRequest request
    ) {
        Deployment deployment = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Deployment not found"
                ));

        try {
            deployment.transitionTo(
                    request.status(),
                    request.failureReason()
            );
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    e.getMessage()
            );
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    e.getMessage()
            );
        }

        if (request.status() == DeploymentStatus.INSTALLED) {
            deployment.getVehicle().installSoftwareVersion(
                    deployment.getRelease().getVersion()
            );
        }

        return DeploymentResponse.from(deployment);
    }

    @Transactional(readOnly = true)
    public DeploymentResponse getById(UUID id) {
        return deploymentRepository.findById(id)
                .map(DeploymentResponse::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Deployment not found"
                ));
    }

    @Transactional(readOnly = true)
    public List<DeploymentResponse> getAll() {
        return deploymentRepository.findAll()
                .stream()
                .map(DeploymentResponse::from)
                .toList();
    }
}
