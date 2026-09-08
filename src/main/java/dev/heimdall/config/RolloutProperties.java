package dev.heimdall.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "heimdall.rollout")
public record RolloutProperties(
        Duration evaluationInterval
) {
}
