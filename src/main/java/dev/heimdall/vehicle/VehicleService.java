package dev.heimdall.vehicle;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class VehicleService {

    private final VehicleRepository vehicleRepository;

    public VehicleService(VehicleRepository vehicleRepository) {
        this.vehicleRepository = vehicleRepository;
    }

    @Transactional
    public VehicleResponse create(CreateVehicleRequest request) {
        if (vehicleRepository.existsByVin(request.vin())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Vehicle with VIN already exists"
            );
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
    public int markStaleVehiclesOffline(Instant cutoff) {
        List<Vehicle> staleVehicles =
                vehicleRepository.findAllByConnectivityStatusAndLastSeenAtBefore(
                        ConnectivityStatus.ONLINE,
                        cutoff
                );

        staleVehicles.forEach(Vehicle::markOffline);

        return staleVehicles.size();
    }

    @Transactional(readOnly = true)
    public VehicleResponse getById(UUID id) {
        return VehicleResponse.from(getVehicle(id));
    }

    @Transactional(readOnly = true)
    public VehicleResponse getByVin(String vin) {
        return vehicleRepository.findByVin(vin)
                .map(VehicleResponse::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Vehicle not found"
                ));
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
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Vehicle not found"
                ));
    }
}
