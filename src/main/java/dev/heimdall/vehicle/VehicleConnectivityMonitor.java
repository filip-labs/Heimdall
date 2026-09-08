package dev.heimdall.vehicle;

import dev.heimdall.config.VehicleProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class VehicleConnectivityMonitor {

    private final VehicleService vehicleService;
    private final VehicleProperties properties;

    @Scheduled(
            fixedDelayString =
                    "${heimdall.vehicle.offline-check-interval:10s}"
    )
    public void detectOfflineVehicles() {
        Instant cutoff =
                Instant.now().minus(properties.offlineThreshold());

        vehicleService.markStaleVehiclesOffline(cutoff);
    }
}
