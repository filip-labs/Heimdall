package dev.heimdall.deployment;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deployment_event")
public class DeploymentEvent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "deployment_id", nullable = false)
    private Deployment deployment;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private DeploymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private DeploymentStatus toStatus;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DeploymentEvent() {
    }

    public DeploymentEvent(
            Deployment deployment,
            DeploymentStatus fromStatus,
            DeploymentStatus toStatus,
            String failureReason
    ) {
        this.id = UUID.randomUUID();
        this.deployment = deployment;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.failureReason = failureReason;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Deployment getDeployment() {
        return deployment;
    }

    public DeploymentStatus getFromStatus() {
        return fromStatus;
    }

    public DeploymentStatus getToStatus() {
        return toStatus;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
