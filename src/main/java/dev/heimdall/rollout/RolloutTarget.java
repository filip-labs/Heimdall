package dev.heimdall.rollout;

import dev.heimdall.vehicle.Vehicle;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "rollout_target")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RolloutTarget {

    @EmbeddedId
    private RolloutTargetId id;

    @MapsId("rolloutId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rollout_id", nullable = false)
    private Rollout rollout;

    @MapsId("vehicleId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Column(name = "target_ordinal", nullable = false)
    private int targetOrdinal;

    public RolloutTarget(Rollout rollout, Vehicle vehicle, int targetOrdinal) {
        this.id = new RolloutTargetId(rollout.getId(), vehicle.getId());
        this.rollout = rollout;
        this.vehicle = vehicle;
        this.targetOrdinal = targetOrdinal;
    }
}
