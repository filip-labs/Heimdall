package dev.heimdall.deployment;

import dev.heimdall.rollout.RolloutStage;
import dev.heimdall.release.SoftwareRelease;
import dev.heimdall.vehicle.Vehicle;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deployment")
public class Deployment {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "release_id", nullable = false)
    private SoftwareRelease release;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rollout_stage_id")
    private RolloutStage rolloutStage;

    @Column(name = "source_software_version", nullable = false, length = 50)
    private String sourceSoftwareVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DeploymentStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Deployment() {
    }

    public Deployment(Vehicle vehicle, SoftwareRelease release) {
        this.id = UUID.randomUUID();
        this.vehicle = vehicle;
        this.release = release;
        this.rolloutStage = null;
        this.sourceSoftwareVersion = vehicle.getSoftwareVersion();
        this.status = DeploymentStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Deployment(
            Vehicle vehicle,
            SoftwareRelease release,
            RolloutStage rolloutStage
    ) {
        this(vehicle, release);
        this.rolloutStage = rolloutStage;
    }

    public void transitionTo(DeploymentStatus nextStatus, String failureReason) {
        if (!isValidTransition(this.status, nextStatus)) {
            throw new IllegalStateException(
                    "Invalid deployment transition: "
                            + this.status + " -> " + nextStatus
            );
        }

        if (nextStatus == DeploymentStatus.FAILED) {
            if (failureReason == null || failureReason.isBlank()) {
                throw new IllegalArgumentException(
                        "Failure reason is required for FAILED deployment"
                );
            }

            this.failureReason = failureReason;
        } else {
            this.failureReason = null;
        }

        this.status = nextStatus;
        this.updatedAt = Instant.now();
    }

    private boolean isValidTransition(
            DeploymentStatus current,
            DeploymentStatus next
    ) {
        if (next == DeploymentStatus.FAILED
                && current != DeploymentStatus.INSTALLED
                && current != DeploymentStatus.FAILED) {
            return true;
        }

        return switch (current) {
            case PENDING -> next == DeploymentStatus.DOWNLOADING;
            case DOWNLOADING -> next == DeploymentStatus.DOWNLOADED;
            case DOWNLOADED -> next == DeploymentStatus.INSTALLING;
            case INSTALLING -> next == DeploymentStatus.INSTALLED;
            case INSTALLED, FAILED -> false;
        };
    }

    public UUID getId() {
        return id;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public SoftwareRelease getRelease() {
        return release;
    }

    public RolloutStage getRolloutStage() {
        return rolloutStage;
    }

    public String getSourceSoftwareVersion() {
        return sourceSoftwareVersion;
    }

    public DeploymentStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
