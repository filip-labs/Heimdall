package dev.heimdall.vehicleauth;

public record VehicleTokenResponse(String accessToken, String tokenType, long expiresIn) {
    @Override
    public String toString() {
        return "VehicleTokenResponse[accessToken=[REDACTED], tokenType=" + tokenType
                + ", expiresIn=" + expiresIn + "]";
    }
}
