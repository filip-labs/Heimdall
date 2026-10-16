//TODO: proveri zasto ove pise da se ova klasa ne koristi
package dev.heimdall.observability;

import dev.heimdall.deployment.DeploymentRepository;
import dev.heimdall.deployment.DeploymentService;
import dev.heimdall.deployment.DeploymentStatus;
import dev.heimdall.rollout.RolloutRepository;
import dev.heimdall.rollout.RolloutStatus;
import dev.heimdall.vehicle.ConnectivityStatus;
import dev.heimdall.vehicle.VehicleRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class HeimdallMetrics {
    public static final String STATUS = "status";

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
                .tag(STATUS, "online")
                .register(registry);

        Gauge.builder(
                        "heimdall.vehicles",
                        repository,
                        value -> value.countByConnectivityStatus(ConnectivityStatus.OFFLINE)
                )
                .description("Current number of vehicles by connectivity status")
                .tag(STATUS, "offline")
                .register(registry);
    }

    private void registerDeploymentGauges(
            MeterRegistry registry,
            DeploymentRepository repository
    ) {
        Gauge.builder(
                        "heimdall.deployments",
                        repository,
                        value -> value.countByStatusIn(
                                DeploymentService.activeStatuses()
                        )
                )
                .description("Current number of deployments by operational status")
                .tag(STATUS, "active")
                .register(registry);

        Gauge.builder(
                        "heimdall.deployments",
                        repository,
                        value -> value.countByStatus(DeploymentStatus.FAILED)
                )
                .description("Current number of deployments by operational status")
                .tag(STATUS, "failed")
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
                .tag(STATUS, "running")
                .register(registry);

        Gauge.builder(
                        "heimdall.rollouts",
                        repository,
                        value -> value.countByStatus(RolloutStatus.PAUSED)
                )
                .description("Current number of rollouts by status")
                .tag(STATUS, "paused")
                .register(registry);
    }
}
