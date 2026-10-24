package dev.heimdall.release;

import java.time.Instant;
import java.util.UUID;

public record SoftwareReleaseResponse(
        UUID id,
        String version,
        String description,
        String artifactUrl,
        String checksum,
        String signatureAlgorithm,
        String signingKeyId,
        String signature,
        Instant createdAt
) {

    public static SoftwareReleaseResponse from(SoftwareRelease release) {
        return new SoftwareReleaseResponse(
                release.getId(),
                release.getVersion(),
                release.getDescription(),
                release.getArtifactUrl(),
                release.getChecksum(),
                release.getSignatureAlgorithm(),
                release.getSigningKeyId(),
                release.getSignature(),
                release.getCreatedAt()
        );
    }
}
