package dev.heimdall.release;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "software_release")
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

    protected SoftwareRelease() {
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

    public UUID getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public String getArtifactUrl() {
        return artifactUrl;
    }

    public String getChecksum() {
        return checksum;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
