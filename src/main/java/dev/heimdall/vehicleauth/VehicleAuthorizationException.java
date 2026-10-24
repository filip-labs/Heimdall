package dev.heimdall.vehicleauth;

public class VehicleAuthorizationException extends RuntimeException {
    public VehicleAuthorizationException() {
        super("Vehicle access forbidden");
    }
}
