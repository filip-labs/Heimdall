package dev.heimdall.deployment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentRepository
        extends JpaRepository<Deployment, UUID> {

    Optional<Deployment> findFirstByVehicle_IdAndStatusInOrderByCreatedAtAsc(
            UUID vehicleId,
            Collection<DeploymentStatus> statuses
    );

    boolean existsByVehicle_IdAndStatusIn(
            UUID vehicleId,
            Collection<DeploymentStatus> statuses
    );

    boolean existsByVehicle_IdInAndStatusIn(
            Collection<UUID> vehicleIds,
            Collection<DeploymentStatus> statuses
    );

    long countByRolloutStage_Id(UUID rolloutStageId);

    long countByRolloutStage_IdAndStatus(
            UUID rolloutStageId,
            DeploymentStatus status
    );

    long countByRolloutStage_IdAndStatusIn(
            UUID rolloutStageId,
            Collection<DeploymentStatus> statuses
    );

}
