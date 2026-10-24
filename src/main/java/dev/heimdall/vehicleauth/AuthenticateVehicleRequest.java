package dev.heimdall.vehicleauth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AuthenticateVehicleRequest(
        @NotNull UUID vehicleId,
        @NotBlank @Size(max = 256) String credential
) {
    @Override
    public String toString() {
        return "AuthenticateVehicleRequest[vehicleId=" + vehicleId + ", credential=[REDACTED]]";
    }
}
