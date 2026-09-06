package dev.heimdall.rollout;

import dev.heimdall.deployment.DeploymentRepository;
import dev.heimdall.deployment.DeploymentService;
import dev.heimdall.release.SoftwareRelease;
import dev.heimdall.vehicle.Vehicle;
import dev.heimdall.vehicle.VehicleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class RolloutTargetSelector {

    private final VehicleRepository vehicleRepository;
    private final DeploymentRepository deploymentRepository;

    public RolloutTargetSelector(
            VehicleRepository vehicleRepository,
            DeploymentRepository deploymentRepository
    ) {
        this.vehicleRepository = vehicleRepository;
        this.deploymentRepository = deploymentRepository;
    }

    public List<Vehicle> selectEligibleTargets(SoftwareRelease release) {
        List<Vehicle> targetVehicles = vehicleRepository
                .findAllBySoftwareVersionNotOrderByVinAsc(release.getVersion());

        if (targetVehicles.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "No eligible vehicles for rollout"
            );
        }

        List<UUID> targetVehicleIds = targetVehicles.stream()
                .map(Vehicle::getId)
                .toList();
        if (deploymentRepository.existsByVehicle_IdInAndStatusIn(
                targetVehicleIds,
                DeploymentService.ACTIVE_STATUSES
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "At least one target vehicle already has an active deployment"
            );
        }

        return targetVehicles;
    }

    public List<RolloutTarget> snapshotTargets(
            Rollout rollout,
            List<Vehicle> targetVehicles
    ) {
        List<RolloutTarget> targets = new ArrayList<>();
        for (int i = 0; i < targetVehicles.size(); i++) {
            targets.add(new RolloutTarget(rollout, targetVehicles.get(i), i + 1));
        }
        return targets;
    }
}
