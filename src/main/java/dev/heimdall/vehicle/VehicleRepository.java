package dev.heimdall.vehicle;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    Optional<Vehicle> findByVin(String vin);

    boolean existsByVin(String vin);

    List<Vehicle> findAllBySoftwareVersionNotOrderByVinAsc(
            String softwareVersion
    );

    List<Vehicle> findAllByConnectivityStatusAndLastSeenAtBefore(
            ConnectivityStatus connectivityStatus,
            Instant cutoff
    );
}
