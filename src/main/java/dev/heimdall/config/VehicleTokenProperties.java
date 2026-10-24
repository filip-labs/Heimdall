package dev.heimdall.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.Base64;

@ConfigurationProperties(prefix = "heimdall.security.vehicle-token")
public record VehicleTokenProperties(String secret, Duration ttl) {
    public VehicleTokenProperties {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(secret == null ? "" : secret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Vehicle token secret must be Base64 encoded");
        }
        if (key.length < 32) {
            throw new IllegalArgumentException("Vehicle token secret must contain at least 32 decoded bytes");
        }
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(1)) < 0
                || ttl.compareTo(Duration.ofMinutes(15)) > 0 || ttl.getNano() != 0) {
            throw new IllegalArgumentException("Vehicle token TTL must be whole seconds between 1s and 15m");
        }
    }

    @Override
    public String toString() {
        return "VehicleTokenProperties[secret=[REDACTED], ttl=" + ttl + "]";
    }
}
