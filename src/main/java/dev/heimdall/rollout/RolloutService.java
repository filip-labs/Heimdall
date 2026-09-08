package dev.heimdall.rollout;

import dev.heimdall.api.ResourceNotFoundException;
import dev.heimdall.release.SoftwareRelease;
import dev.heimdall.release.SoftwareReleaseRepository;
import dev.heimdall.vehicle.Vehicle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RolloutService {

    private final RolloutRepository rolloutRepository;
    private final RolloutStageRepository rolloutStageRepository;
    private final RolloutTargetRepository rolloutTargetRepository;
    private final SoftwareReleaseRepository releaseRepository;
    private final RolloutTargetSelector targetSelector;
    private final RolloutStagePlanner stagePlanner;
    private final RolloutDeploymentCoordinator deploymentCoordinator;
    private final RolloutStageResponseMapper stageResponseMapper;

    @Transactional
    public RolloutResponse create(CreateRolloutRequest request) {
        SoftwareRelease release = releaseRepository.findById(request.releaseId())
                .orElseThrow(() -> new ResourceNotFoundException("Software release not found"));

        List<Vehicle> targetVehicles = targetSelector.selectEligibleTargets(release);

        Rollout rollout = rolloutRepository.save(new Rollout(
                release,
                request.failureThresholdPercent(),
                Boolean.TRUE.equals(request.automaticRollbackEnabled()),
                targetVehicles.size()
        ));

        // Rollout membership is intentionally snapshot-based; later registrations
        // must not change the cohort being advanced through stages.
        rolloutTargetRepository.saveAll(targetSelector.snapshotTargets(rollout, targetVehicles));

        List<RolloutStage> stages = stagePlanner.planStages(
                rollout,
                request.stages(),
                targetVehicles.size()
        );
        rolloutStageRepository.saveAll(stages);

        deploymentCoordinator.createDeploymentsForStage(rollout, stages.getFirst());

        return RolloutResponse.from(rollout);
    }

    @Transactional(readOnly = true)
    public List<RolloutResponse> getAll() {
        return rolloutRepository.findAll()
                .stream()
                .map(RolloutResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public RolloutResponse getById(UUID id) {
        return rolloutRepository.findById(id)
                .map(RolloutResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Rollout not found"));
    }

    @Transactional(readOnly = true)
    public List<RolloutStageResponse> getStages(UUID rolloutId) {
        if (!rolloutRepository.existsById(rolloutId)) {
            throw new ResourceNotFoundException("Rollout not found");
        }

        return rolloutStageRepository.findAllByRollout_IdOrderByStageIndexAsc(rolloutId)
                .stream()
                .map(stageResponseMapper::from)
                .toList();
    }
}
