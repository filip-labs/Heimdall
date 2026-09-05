package dev.heimdall.rollout;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rollout_stage")
public class RolloutStage {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rollout_id", nullable = false)
    private Rollout rollout;

    @Column(name = "stage_index", nullable = false)
    private int stageIndex;

    @Column(name = "target_percentage", nullable = false)
    private int targetPercentage;

    @Column(name = "target_vehicle_count", nullable = false)
    private int targetVehicleCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RolloutStageStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected RolloutStage() {
    }

    public RolloutStage(
            Rollout rollout,
            int stageIndex,
            int targetPercentage,
            int targetVehicleCount,
            RolloutStageStatus status
    ) {
        this.id = UUID.randomUUID();
        this.rollout = rollout;
        this.stageIndex = stageIndex;
        this.targetPercentage = targetPercentage;
        this.targetVehicleCount = targetVehicleCount;
        this.status = status;
        if (status == RolloutStageStatus.RUNNING) {
            this.startedAt = Instant.now();
        }
    }

    public void start() {
        this.status = RolloutStageStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    public void complete() {
        this.status = RolloutStageStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void fail() {
        this.status = RolloutStageStatus.FAILED;
        this.completedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Rollout getRollout() {
        return rollout;
    }

    public int getStageIndex() {
        return stageIndex;
    }

    public int getTargetPercentage() {
        return targetPercentage;
    }

    public int getTargetVehicleCount() {
        return targetVehicleCount;
    }

    public RolloutStageStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
