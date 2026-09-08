package dev.heimdall.vehicle;

import dev.heimdall.api.ConflictException;
import dev.heimdall.api.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleRepository vehicleRepository;

    @Transactional
    public VehicleResponse create(CreateVehicleRequest request) {
        if (vehicleRepository.existsByVin(request.vin())) {
            throw new ConflictException("Vehicle with VIN already exists");
        }

        Vehicle vehicle = new Vehicle(
                request.vin(),
                request.softwareVersion()
        );

        return VehicleResponse.from(
                vehicleRepository.save(vehicle)
        );
    }

    @Transactional
    public VehicleResponse heartbeat(UUID id) {
        Vehicle vehicle = getVehicle(id);

        vehicle.heartbeat();

        return VehicleResponse.from(vehicle);
    }

    @Transactional
    public void markStaleVehiclesOffline(Instant cutoff) {
        List<Vehicle> staleVehicles =
                vehicleRepository.findAllByConnectivityStatusAndLastSeenAtBefore(
                        ConnectivityStatus.ONLINE,
                        cutoff
                );

        staleVehicles.forEach(Vehicle::markOffline);
    }

    @Transactional(readOnly = true)
    public VehicleResponse getById(UUID id) {
        return VehicleResponse.from(getVehicle(id));
    }

    @Transactional(readOnly = true)
    public VehicleResponse getByVin(String vin) {
        return vehicleRepository.findByVin(vin)
                .map(VehicleResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found"));
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> getAll() {
        return vehicleRepository.findAll()
                .stream()
                .map(VehicleResponse::from)
                .toList();
    }

    private Vehicle getVehicle(UUID id) {
        return vehicleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found"));
    }
}
