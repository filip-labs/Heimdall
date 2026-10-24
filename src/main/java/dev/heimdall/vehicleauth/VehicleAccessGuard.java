package dev.heimdall.vehicleauth;

import java.util.UUID;

public final class VehicleAccessGuard {
    private VehicleAccessGuard() { }

    public static void requireOwner(UUID authenticatedVehicle, UUID resourceVehicle) {
        if (!resourceVehicle.equals(authenticatedVehicle)) {
            throw new VehicleAuthorizationException();
        }
    }
}
