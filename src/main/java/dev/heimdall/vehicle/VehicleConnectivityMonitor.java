package dev.heimdall.vehicle;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class VehicleConnectivityMonitor {

    private final VehicleService vehicleService;
    private final long offlineThresholdSeconds;

    public VehicleConnectivityMonitor(
            VehicleService vehicleService,
            @Value("${heimdall.vehicle.offline-threshold-seconds:30}")
            long offlineThresholdSeconds
    ) {
        this.vehicleService = vehicleService;
        this.offlineThresholdSeconds = offlineThresholdSeconds;
    }

    @Scheduled(
            fixedDelayString =
                    "${heimdall.vehicle.offline-check-interval-ms:10000}"
    )
    public void detectOfflineVehicles() {
        Instant cutoff =
                Instant.now().minusSeconds(offlineThresholdSeconds);

        vehicleService.markStaleVehiclesOffline(cutoff);
    }
}
