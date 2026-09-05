package dev.heimdall.vehicle;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

    @Transactional(readOnly = true)
    public VehicleResponse getById(UUID id) {
        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Vehicle not found"
                ));

        return VehicleResponse.from(vehicle);
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> getAll() {
        return vehicleRepository.findAll()
                .stream()
                .map(VehicleResponse::from)
                .toList();
    }
}
