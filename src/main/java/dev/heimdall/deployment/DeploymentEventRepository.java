package dev.heimdall.deployment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeploymentEventRepository
        extends JpaRepository<DeploymentEvent, UUID> {

    List<DeploymentEvent> findAllByDeployment_IdOrderByCreatedAtAsc(
            UUID deploymentId
    );
}
