package dev.heimdall.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "heimdall.vehicle")
public record VehicleProperties(
        Duration offlineThreshold,
        Duration offlineCheckInterval
) {
}
