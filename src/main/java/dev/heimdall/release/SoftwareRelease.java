package dev.heimdall.release;

import dev.heimdall.releasesigning.ReleaseSignature;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "software_release")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SoftwareRelease {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String version;

    @Column(length = 500)
    private String description;

    @Column(name = "artifact_url", nullable = false, length = 500)
    private String artifactUrl;

    @Column(nullable = false, length = 128)
    private String checksum;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "signature_algorithm", length = 20)
    private String signatureAlgorithm;

    @Column(name = "signing_key_id", length = 100)
    private String signingKeyId;

    @Column(length = 86)
    private String signature;

    public SoftwareRelease(String version, String description, String artifactUrl,
            String checksum, ReleaseSignature signed) {
        this(version, description, artifactUrl, checksum);
        this.signatureAlgorithm = signed.algorithm();
        this.signingKeyId = signed.keyId();
        this.signature = signed.signature();
    }

    public SoftwareRelease(
            String version,
            String description,
            String artifactUrl,
            String checksum
    ) {
        this.id = UUID.randomUUID();
        this.version = version;
        this.description = description;
        this.artifactUrl = artifactUrl;
        this.checksum = checksum;
        this.createdAt = Instant.now();
    }
}
