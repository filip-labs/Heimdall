package dev.heimdall.vehicleauth;

public class VehicleAuthenticationException extends RuntimeException {
    public VehicleAuthenticationException() {
        super("Vehicle authentication failed");
    }
}
