package dev.heimdall.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "heimdall.security.release-signing")
public record ReleaseSigningProperties(String privateKey, String keyId) {
    public ReleaseSigningProperties {
        if (privateKey == null || privateKey.isBlank()) {
            throw new IllegalArgumentException("Release signing private key is required");
        }
        if (keyId == null || keyId.isBlank() || keyId.length() > 100) {
            throw new IllegalArgumentException("Release signing key ID must contain 1 to 100 characters");
        }
    }

    @Override
    public String toString() {
        return "ReleaseSigningProperties[privateKey=[REDACTED], keyId=" + keyId + "]";
    }
}
