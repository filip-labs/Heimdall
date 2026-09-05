package dev.heimdall.rollout;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RolloutRepository extends JpaRepository<Rollout, UUID> {

    List<Rollout> findAllByStatus(RolloutStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Rollout r where r.id = :id")
    Optional<Rollout> findByIdForUpdate(UUID id);
}
