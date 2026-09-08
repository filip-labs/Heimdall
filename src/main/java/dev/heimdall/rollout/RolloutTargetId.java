package dev.heimdall.rollout;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RolloutTargetId implements Serializable {

    @Column(name = "rollout_id")
    private UUID rolloutId;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    public RolloutTargetId(UUID rolloutId, UUID vehicleId) {
        this.rolloutId = rolloutId;
        this.vehicleId = vehicleId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RolloutTargetId that)) {
            return false;
        }
        return Objects.equals(rolloutId, that.rolloutId)
                && Objects.equals(vehicleId, that.vehicleId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rolloutId, vehicleId);
    }
}
