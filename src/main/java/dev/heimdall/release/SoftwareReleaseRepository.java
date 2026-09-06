package dev.heimdall.release;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SoftwareReleaseRepository
        extends JpaRepository<SoftwareRelease, UUID> {

    boolean existsByVersion(String version);
}
