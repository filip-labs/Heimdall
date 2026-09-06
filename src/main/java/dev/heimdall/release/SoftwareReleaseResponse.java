package dev.heimdall.release;

import java.time.Instant;
import java.util.UUID;

public record SoftwareReleaseResponse(
        UUID id,
        String version,
        String description,
        String artifactUrl,
        String checksum,
        Instant createdAt
) {

    public static SoftwareReleaseResponse from(SoftwareRelease release) {
        return new SoftwareReleaseResponse(
                release.getId(),
                release.getVersion(),
                release.getDescription(),
                release.getArtifactUrl(),
                release.getChecksum(),
                release.getCreatedAt()
        );
    }
}
