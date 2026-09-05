package dev.heimdall.rollout;

import dev.heimdall.vehicle.SoftwareRelease;
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
            int totalVehicles
    ) {
        Instant now = Instant.now();

        this.id = UUID.randomUUID();
        this.release = release;
        this.status = RolloutStatus.RUNNING;
        this.failureThresholdPercent = failureThresholdPercent;
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
        this.status = RolloutStatus.PAUSED;
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
