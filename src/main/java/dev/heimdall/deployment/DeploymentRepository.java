package dev.heimdall.deployment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DeploymentRepository
        extends JpaRepository<Deployment, UUID> {
}
