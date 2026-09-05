package dev.heimdall.vehicle;

import java.time.Instant;
import java.util.UUID;

public record VehicleResponse(
        UUID id,
        String vin,
        String softwareVersion,
        ConnectivityStatus connectivityStatus,
        Instant lastSeenAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static VehicleResponse from(Vehicle vehicle) {
        return new VehicleResponse(
                vehicle.getId(),
                vehicle.getVin(),
                vehicle.getSoftwareVersion(),
                vehicle.getConnectivityStatus(),
                vehicle.getLastSeenAt(),
                vehicle.getCreatedAt(),
                vehicle.getUpdatedAt()
        );
    }
}
