package dev.heimdall.deployment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentRepository
        extends JpaRepository<Deployment, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Deployment d where d.id = :id")
    Optional<Deployment> findByIdForUpdate(UUID id);

    Optional<Deployment> findByRollbackOfDeployment_Id(UUID deploymentId);

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

    @Query("""
            select d.id
            from Deployment d
            join d.vehicle v
            where d.rolloutStage.id = :rolloutStageId
              and d.status = :status
            order by v.vin asc
            """)
    List<UUID> findIdsByRolloutStageIdAndStatusOrderByVehicleVinAsc(
            UUID rolloutStageId,
            DeploymentStatus status
    );

}
