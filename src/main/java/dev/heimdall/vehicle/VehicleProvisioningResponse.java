package dev.heimdall.vehicle;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record VehicleProvisioningResponse(
        @JsonUnwrapped VehicleResponse vehicle,
        String credential
) {
    @Override
    public String toString() {
        return "VehicleProvisioningResponse[vehicle=" + vehicle + ", credential=[REDACTED]]";
    }
}
