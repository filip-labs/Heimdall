package dev.heimdall.release;

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
