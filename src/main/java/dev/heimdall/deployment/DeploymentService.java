package dev.heimdall.deployment;

import dev.heimdall.api.BadRequestException;
import dev.heimdall.api.ConflictException;
import dev.heimdall.api.ResourceNotFoundException;
import dev.heimdall.release.SoftwareRelease;
import dev.heimdall.release.SoftwareReleaseRepository;
import dev.heimdall.rollout.RolloutStage;
import dev.heimdall.vehicle.Vehicle;
import dev.heimdall.vehicle.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeploymentService {

    private final DeploymentRepository deploymentRepository;
    private final DeploymentEventRepository deploymentEventRepository;
    private final VehicleRepository vehicleRepository;
    private final SoftwareReleaseRepository releaseRepository;

    @Transactional
    public DeploymentResponse create(CreateDeploymentRequest request) {
        Vehicle vehicle = vehicleRepository.findById(request.vehicleId())
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found"));

        SoftwareRelease release = releaseRepository.findById(request.releaseId())
                .orElseThrow(() -> new ResourceNotFoundException("Software release not found"));

        if (vehicle.getSoftwareVersion().equals(release.getVersion())) {
            throw new ConflictException("Vehicle is already running this software version");
        }

        if (deploymentRepository.existsByVehicle_IdAndStatusIn(
                vehicle.getId(),
                ACTIVE_STATUSES
        )) {
            throw new ConflictException("Vehicle already has an active deployment");
        }

        return DeploymentResponse.from(createDeployment(vehicle, release, null, null));
    }

    public void createForRolloutStage(
            Vehicle vehicle,
            SoftwareRelease release,
            RolloutStage rolloutStage
    ) {
        if (vehicle.getSoftwareVersion().equals(release.getVersion())) {
            throw new ConflictException("Vehicle is already running this software version");
        }

        if (deploymentRepository.existsByVehicle_IdAndStatusIn(
                vehicle.getId(),
                ACTIVE_STATUSES
        )) {
            throw new ConflictException("Vehicle already has an active deployment");
        }

        createDeployment(vehicle, release, rolloutStage, null);
    }

    Deployment createRollbackDeployment(
            Vehicle vehicle,
            SoftwareRelease release,
            Deployment rollbackOfDeployment
    ) {
        return createDeployment(vehicle, release, null, rollbackOfDeployment);
    }

    private Deployment createDeployment(
            Vehicle vehicle,
            SoftwareRelease release,
            RolloutStage rolloutStage,
            Deployment rollbackOfDeployment
    ) {
        Deployment deployment = rollbackOfDeployment == null
                ? new Deployment(vehicle, release, rolloutStage)
                : new Deployment(vehicle, release, rollbackOfDeployment);
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
            throw new ResourceNotFoundException("Deployment not found");
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
                .orElseThrow(() -> new ResourceNotFoundException("Deployment not found"));

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
            throw new ConflictException(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
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
                .orElseThrow(() -> new ResourceNotFoundException("Deployment not found"));
    }

    @Transactional(readOnly = true)
    public List<DeploymentResponse> getAll() {
        return deploymentRepository.findAll()
                .stream()
                .map(DeploymentResponse::from)
                .toList();
    }

    private static final Set<DeploymentStatus> ACTIVE_STATUSES =
            Set.of(
                    DeploymentStatus.PENDING,
                    DeploymentStatus.DOWNLOADING,
                    DeploymentStatus.DOWNLOADED,
                    DeploymentStatus.INSTALLING
            );

    public static Set<DeploymentStatus> activeStatuses() {
        return ACTIVE_STATUSES;
    }

    @Transactional(readOnly = true)
    public Optional<DeploymentResponse> getActiveForVehicle(UUID vehicleId) {
        if (!vehicleRepository.existsById(vehicleId)) {
            throw new ResourceNotFoundException("Vehicle not found");
        }

        return deploymentRepository
                .findFirstByVehicle_IdAndStatusInOrderByCreatedAtAsc(
                        vehicleId,
                        ACTIVE_STATUSES
                )
                .map(DeploymentResponse::from);
    }
}
