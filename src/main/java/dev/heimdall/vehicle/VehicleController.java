package dev.heimdall.vehicle;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vehicles")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleResponse create(
            @Valid @RequestBody CreateVehicleRequest request
    ) {
        return vehicleService.create(request);
    }

    @PostMapping("/{id}/heartbeat")
    public VehicleResponse heartbeat(@PathVariable UUID id) {
        return vehicleService.heartbeat(id);
    }

    @GetMapping("/{id}")
    public VehicleResponse getById(@PathVariable UUID id) {
        return vehicleService.getById(id);
    }

    @GetMapping("/by-vin/{vin}")
    public VehicleResponse getByVin(@PathVariable String vin) {
        return vehicleService.getByVin(vin);
    }

    @GetMapping
    public List<VehicleResponse> getAll() {
        return vehicleService.getAll();
    }
}
