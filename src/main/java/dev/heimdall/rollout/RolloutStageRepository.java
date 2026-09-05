package dev.heimdall.rollout;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RolloutStageRepository
        extends JpaRepository<RolloutStage, UUID> {

    List<RolloutStage> findAllByRollout_IdOrderByStageIndexAsc(UUID rolloutId);

    Optional<RolloutStage> findByRollout_IdAndStageIndex(
            UUID rolloutId,
            int stageIndex
    );
}
