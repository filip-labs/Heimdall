package dev.heimdall.rollout;

import dev.heimdall.deployment.DeploymentRepository;
import dev.heimdall.deployment.DeploymentService;
import dev.heimdall.deployment.DeploymentStatus;
import dev.heimdall.vehicle.SoftwareRelease;
import dev.heimdall.vehicle.SoftwareReleaseRepository;
import dev.heimdall.vehicle.Vehicle;
import dev.heimdall.vehicle.VehicleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class RolloutService {

    private static final List<DeploymentStatus> TERMINAL_STATUSES =
            List.of(DeploymentStatus.INSTALLED, DeploymentStatus.FAILED);

    private final RolloutRepository rolloutRepository;
    private final RolloutStageRepository rolloutStageRepository;
    private final RolloutTargetRepository rolloutTargetRepository;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentService deploymentService;
    private final VehicleRepository vehicleRepository;
    private final SoftwareReleaseRepository releaseRepository;
    private final TransactionTemplate transactionTemplate;

    public RolloutService(
            RolloutRepository rolloutRepository,
            RolloutStageRepository rolloutStageRepository,
            RolloutTargetRepository rolloutTargetRepository,
            DeploymentRepository deploymentRepository,
            DeploymentService deploymentService,
            VehicleRepository vehicleRepository,
            SoftwareReleaseRepository releaseRepository,
            TransactionTemplate transactionTemplate
    ) {
        this.rolloutRepository = rolloutRepository;
        this.rolloutStageRepository = rolloutStageRepository;
        this.rolloutTargetRepository = rolloutTargetRepository;
        this.deploymentRepository = deploymentRepository;
        this.deploymentService = deploymentService;
        this.vehicleRepository = vehicleRepository;
        this.releaseRepository = releaseRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Transactional
    public RolloutResponse create(CreateRolloutRequest request) {
        SoftwareRelease release = releaseRepository.findById(request.releaseId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Software release not found"
                ));

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

        Rollout rollout = rolloutRepository.save(new Rollout(
                release,
                request.failureThresholdPercent(),
                targetVehicles.size()
        ));

        List<RolloutTarget> targets = new ArrayList<>();
        for (int i = 0; i < targetVehicles.size(); i++) {
            targets.add(new RolloutTarget(rollout, targetVehicles.get(i), i + 1));
        }
        rolloutTargetRepository.saveAll(targets);

        List<RolloutStage> stages = createStages(
                rollout,
                request.stages(),
                targetVehicles.size()
        );
        rolloutStageRepository.saveAll(stages);

        createDeploymentsForStage(rollout, stages.getFirst());

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
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Rollout not found"
                ));
    }

    @Transactional(readOnly = true)
    public List<RolloutStageResponse> getStages(UUID rolloutId) {
        if (!rolloutRepository.existsById(rolloutId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Rollout not found"
            );
        }

        return rolloutStageRepository.findAllByRollout_IdOrderByStageIndexAsc(rolloutId)
                .stream()
                .map(this::toStageResponse)
                .toList();
    }

    @Scheduled(
            fixedDelayString = "${heimdall.rollout.evaluation-interval-ms:1000}",
            initialDelayString = "${heimdall.rollout.evaluation-interval-ms:1000}"
    )
    public void evaluateRunningRollouts() {
        List<UUID> rolloutIds = rolloutRepository.findAllByStatus(RolloutStatus.RUNNING)
                .stream()
                .map(Rollout::getId)
                .toList();

        for (UUID rolloutId : rolloutIds) {
            transactionTemplate.executeWithoutResult(status -> evaluateRollout(rolloutId));
        }
    }

    @Transactional
    public void evaluateRollout(UUID rolloutId) {
        Rollout rollout = rolloutRepository.findByIdForUpdate(rolloutId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Rollout not found"
                ));

        if (rollout.getStatus() != RolloutStatus.RUNNING) {
            return;
        }

        RolloutStage stage = rolloutStageRepository
                .findByRollout_IdAndStageIndex(
                        rollout.getId(),
                        rollout.getCurrentStageIndex()
                )
                .orElseThrow(() -> new IllegalStateException("Current rollout stage not found"));

        if (stage.getStatus() != RolloutStageStatus.RUNNING) {
            return;
        }

        long deploymentCount = deploymentRepository.countByRolloutStage_Id(stage.getId());
        long terminalCount = deploymentRepository.countByRolloutStage_IdAndStatusIn(
                stage.getId(),
                TERMINAL_STATUSES
        );

        if (terminalCount < deploymentCount) {
            return;
        }

        long failedCount = deploymentRepository.countByRolloutStage_IdAndStatus(
                stage.getId(),
                DeploymentStatus.FAILED
        );
        BigDecimal failureRate = deploymentCount == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(failedCount)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(deploymentCount), 4, RoundingMode.HALF_UP);

        if (failureRate.compareTo(rollout.getFailureThresholdPercent()) > 0) {
            stage.fail();
            rollout.pause();
            return;
        }

        stage.complete();

        List<RolloutStage> stages = rolloutStageRepository
                .findAllByRollout_IdOrderByStageIndexAsc(rollout.getId());
        int nextStageIndex = rollout.getCurrentStageIndex() + 1;
        if (nextStageIndex >= stages.size()) {
            rollout.complete();
            return;
        }

        RolloutStage nextStage = stages.get(nextStageIndex);
        nextStage.start();
        rollout.advanceToStage(nextStageIndex);
        createDeploymentsForStage(rollout, nextStage);
    }

    private List<RolloutStage> createStages(
            Rollout rollout,
            List<Integer> requestedStages,
            int totalVehicles
    ) {
        List<RolloutStage> stages = new ArrayList<>();
        for (int i = 0; i < requestedStages.size(); i++) {
            int percentage = requestedStages.get(i);
            int targetVehicleCount = i == requestedStages.size() - 1
                    ? totalVehicles
                    : (int) Math.ceil(totalVehicles * percentage / 100.0);
            stages.add(new RolloutStage(
                    rollout,
                    i,
                    percentage,
                    targetVehicleCount,
                    i == 0 ? RolloutStageStatus.RUNNING : RolloutStageStatus.PENDING
            ));
        }
        return stages;
    }

    private void createDeploymentsForStage(
            Rollout rollout,
            RolloutStage stage
    ) {
        int previousTargetCount = stage.getStageIndex() == 0
                ? 0
                : rolloutStageRepository
                        .findByRollout_IdAndStageIndex(
                                rollout.getId(),
                                stage.getStageIndex() - 1
                        )
                        .orElseThrow(() -> new IllegalStateException("Previous rollout stage not found"))
                        .getTargetVehicleCount();

        if (stage.getTargetVehicleCount() <= previousTargetCount) {
            return;
        }

        List<RolloutTarget> targets = rolloutTargetRepository
                .findAllByRollout_IdAndTargetOrdinalBetweenOrderByTargetOrdinalAsc(
                        rollout.getId(),
                        previousTargetCount + 1,
                        stage.getTargetVehicleCount()
                );

        for (RolloutTarget target : targets) {
            deploymentService.createForRolloutStage(
                    target.getVehicle(),
                    rollout.getRelease(),
                    stage
            );
        }
    }

    private RolloutStageResponse toStageResponse(RolloutStage stage) {
        long deploymentCount = deploymentRepository.countByRolloutStage_Id(stage.getId());
        long installedCount = deploymentRepository.countByRolloutStage_IdAndStatus(
                stage.getId(),
                DeploymentStatus.INSTALLED
        );
        long failedCount = deploymentRepository.countByRolloutStage_IdAndStatus(
                stage.getId(),
                DeploymentStatus.FAILED
        );

        return new RolloutStageResponse(
                stage.getId(),
                stage.getStageIndex(),
                stage.getTargetPercentage(),
                stage.getTargetVehicleCount(),
                stage.getStatus(),
                deploymentCount,
                installedCount,
                failedCount,
                stage.getStartedAt(),
                stage.getCompletedAt()
        );
    }
}
