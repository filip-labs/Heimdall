package dev.heimdall.observability;

import dev.heimdall.deployment.DeploymentRepository;
import dev.heimdall.deployment.DeploymentStatus;
import dev.heimdall.rollout.RolloutRepository;
import dev.heimdall.rollout.RolloutStatus;
import dev.heimdall.vehicle.ConnectivityStatus;
import dev.heimdall.vehicle.VehicleRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumSet;

@Component
public class HeimdallMetrics {

    private static final EnumSet<DeploymentStatus> ACTIVE_DEPLOYMENT_STATUSES =
            EnumSet.of(
                    DeploymentStatus.PENDING,
                    DeploymentStatus.DOWNLOADING,
                    DeploymentStatus.DOWNLOADED,
                    DeploymentStatus.INSTALLING
            );

    public HeimdallMetrics(
            MeterRegistry meterRegistry,
            VehicleRepository vehicleRepository,
            DeploymentRepository deploymentRepository,
            RolloutRepository rolloutRepository
    ) {
        registerVehicleGauges(meterRegistry, vehicleRepository);
        registerDeploymentGauges(meterRegistry, deploymentRepository);
        registerRolloutGauges(meterRegistry, rolloutRepository);
    }

    private void registerVehicleGauges(
            MeterRegistry registry,
            VehicleRepository repository
    ) {
        Gauge.builder(
                        "heimdall.vehicles",
                        repository,
                        value -> value.countByConnectivityStatus(ConnectivityStatus.ONLINE)
                )
                .description("Current number of vehicles by connectivity status")
                .tag("status", "online")
                .register(registry);

        Gauge.builder(
                        "heimdall.vehicles",
                        repository,
                        value -> value.countByConnectivityStatus(ConnectivityStatus.OFFLINE)
                )
                .description("Current number of vehicles by connectivity status")
                .tag("status", "offline")
                .register(registry);
    }

    private void registerDeploymentGauges(
            MeterRegistry registry,
            DeploymentRepository repository
    ) {
        Gauge.builder(
                        "heimdall.deployments",
                        repository,
                        value -> value.countByStatusIn(ACTIVE_DEPLOYMENT_STATUSES)
                )
                .description("Current number of deployments by operational status")
                .tag("status", "active")
                .register(registry);

        Gauge.builder(
                        "heimdall.deployments",
                        repository,
                        value -> value.countByStatus(DeploymentStatus.FAILED)
                )
                .description("Current number of deployments by operational status")
                .tag("status", "failed")
                .register(registry);
    }

    private void registerRolloutGauges(
            MeterRegistry registry,
            RolloutRepository repository
    ) {
        Gauge.builder(
                        "heimdall.rollouts",
                        repository,
                        value -> value.countByStatus(RolloutStatus.RUNNING)
                )
                .description("Current number of rollouts by status")
                .tag("status", "running")
                .register(registry);

        Gauge.builder(
                        "heimdall.rollouts",
                        repository,
                        value -> value.countByStatus(RolloutStatus.PAUSED)
                )
                .description("Current number of rollouts by status")
                .tag("status", "paused")
                .register(registry);
    }
}
