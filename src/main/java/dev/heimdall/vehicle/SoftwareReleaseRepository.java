package dev.heimdall.vehicle;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SoftwareReleaseRepository
        extends JpaRepository<SoftwareRelease, UUID> {

    Optional<SoftwareRelease> findByVersion(String version);

    boolean existsByVersion(String version);
}
