package dev.heimdall.rollout;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RolloutTargetRepository
        extends JpaRepository<RolloutTarget, RolloutTargetId> {

    long countByRollout_Id(UUID rolloutId);

    @EntityGraph(attributePaths = "vehicle")
    List<RolloutTarget> findAllByRollout_IdAndTargetOrdinalBetweenOrderByTargetOrdinalAsc(
            UUID rolloutId,
            int firstOrdinal,
            int lastOrdinal
    );
}
