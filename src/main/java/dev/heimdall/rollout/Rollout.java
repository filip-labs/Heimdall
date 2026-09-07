package dev.heimdall.rollout;

import dev.heimdall.release.SoftwareRelease;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rollout")
public class Rollout {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "release_id", nullable = false)
    private SoftwareRelease release;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RolloutStatus status;

    @Column(name = "failure_threshold_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal failureThresholdPercent;

    @Column(name = "automatic_rollback_enabled", nullable = false)
    private boolean automaticRollbackEnabled;

    @Column(name = "total_vehicles", nullable = false)
    private int totalVehicles;

    @Column(name = "current_stage_index", nullable = false)
    private int currentStageIndex;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Rollout() {
    }

    public Rollout(
            SoftwareRelease release,
            BigDecimal failureThresholdPercent,
            boolean automaticRollbackEnabled,
            int totalVehicles
    ) {
        Instant now = Instant.now();

        this.id = UUID.randomUUID();
        this.release = release;
        this.status = RolloutStatus.RUNNING;
        this.failureThresholdPercent = failureThresholdPercent;
        this.automaticRollbackEnabled = automaticRollbackEnabled;
        this.totalVehicles = totalVehicles;
        this.currentStageIndex = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void advanceToStage(int stageIndex) {
        this.currentStageIndex = stageIndex;
        this.updatedAt = Instant.now();
    }

    public void pause() {
        if (this.status == RolloutStatus.PAUSED) {
            return;
        }

        if (this.status != RolloutStatus.RUNNING) {
            throw new IllegalStateException(
                    "Rollout cannot be paused from status " + this.status
            );
        }

        this.status = RolloutStatus.PAUSED;
        this.updatedAt = Instant.now();
    }

    public void resume() {
        if (this.status == RolloutStatus.RUNNING) {
            return;
        }

        if (this.status != RolloutStatus.PAUSED) {
            throw new IllegalStateException(
                    "Rollout cannot be resumed from status " + this.status
            );
        }

        this.status = RolloutStatus.RUNNING;
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        if (this.status == RolloutStatus.CANCELLED) {
            return;
        }

        if (this.status != RolloutStatus.RUNNING &&
                this.status != RolloutStatus.PAUSED) {
            throw new IllegalStateException(
                    "Rollout cannot be cancelled from status " + this.status
            );
        }

        this.status = RolloutStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    public void complete() {
        this.status = RolloutStatus.COMPLETED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public SoftwareRelease getRelease() {
        return release;
    }

    public RolloutStatus getStatus() {
        return status;
    }

    public BigDecimal getFailureThresholdPercent() {
        return failureThresholdPercent;
    }

    public boolean isAutomaticRollbackEnabled() {
        return automaticRollbackEnabled;
    }

    public int getTotalVehicles() {
        return totalVehicles;
    }

    public int getCurrentStageIndex() {
        return currentStageIndex;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
