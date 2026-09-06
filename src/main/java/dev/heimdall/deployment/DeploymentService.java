package dev.heimdall.deployment;

import dev.heimdall.rollout.RolloutStage;
import dev.heimdall.release.SoftwareRelease;
import dev.heimdall.release.SoftwareReleaseRepository;
import dev.heimdall.vehicle.Vehicle;
import dev.heimdall.vehicle.VehicleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import java.util.List;
import java.util.UUID;

@Service
public class DeploymentService {

    private final DeploymentRepository deploymentRepository;
    private final DeploymentEventRepository deploymentEventRepository;
    private final VehicleRepository vehicleRepository;
    private final SoftwareReleaseRepository releaseRepository;

    public DeploymentService(
            DeploymentRepository deploymentRepository,
            DeploymentEventRepository deploymentEventRepository,
            VehicleRepository vehicleRepository,
            SoftwareReleaseRepository releaseRepository
    ) {
        this.deploymentRepository = deploymentRepository;
        this.deploymentEventRepository = deploymentEventRepository;
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

        if (deploymentRepository.existsByVehicle_IdAndStatusIn(
                vehicle.getId(),
                ACTIVE_STATUSES
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Vehicle already has an active deployment"
            );
        }

        return DeploymentResponse.from(createDeployment(vehicle, release, null));
    }

    public Deployment createForRolloutStage(
            Vehicle vehicle,
            SoftwareRelease release,
            RolloutStage rolloutStage
    ) {
        if (vehicle.getSoftwareVersion().equals(release.getVersion())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Vehicle is already running this software version"
            );
        }

        if (deploymentRepository.existsByVehicle_IdAndStatusIn(
                vehicle.getId(),
                ACTIVE_STATUSES
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Vehicle already has an active deployment"
            );
        }

        return createDeployment(vehicle, release, rolloutStage);
    }

    private Deployment createDeployment(
            Vehicle vehicle,
            SoftwareRelease release,
            RolloutStage rolloutStage
    ) {
        Deployment deployment = new Deployment(vehicle, release, rolloutStage);
        Deployment savedDeployment = deploymentRepository.save(deployment);

        deploymentEventRepository.save(new DeploymentEvent(
                savedDeployment,
                null,
                DeploymentStatus.PENDING,
                null
        ));

        return savedDeployment;
    }

    @Transactional(readOnly = true)
    public List<DeploymentEventResponse> getEvents(UUID deploymentId) {
        if (!deploymentRepository.existsById(deploymentId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Deployment not found"
            );
        }

        return deploymentEventRepository
                .findAllByDeployment_IdOrderByCreatedAtAsc(deploymentId)
                .stream()
                .map(DeploymentEventResponse::from)
                .toList();
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

        if (deployment.getStatus() == request.status()) {
            return DeploymentResponse.from(deployment);
        }

        DeploymentStatus previousStatus = deployment.getStatus();

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

        deploymentEventRepository.save(new DeploymentEvent(
                deployment,
                previousStatus,
                deployment.getStatus(),
                deployment.getFailureReason()
        ));

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

    public static final Set<DeploymentStatus> ACTIVE_STATUSES =
            EnumSet.of(
                    DeploymentStatus.PENDING,
                    DeploymentStatus.DOWNLOADING,
                    DeploymentStatus.DOWNLOADED,
                    DeploymentStatus.INSTALLING
            );

    @Transactional(readOnly = true)
    public Optional<DeploymentResponse> getActiveForVehicle(UUID vehicleId) {
        if (!vehicleRepository.existsById(vehicleId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Vehicle not found"
            );
        }

        return deploymentRepository
                .findFirstByVehicle_IdAndStatusInOrderByCreatedAtAsc(
                        vehicleId,
                        ACTIVE_STATUSES
                )
                .map(DeploymentResponse::from);
    }
}
