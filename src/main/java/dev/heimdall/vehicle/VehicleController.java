package dev.heimdall.vehicle;

import jakarta.validation.Valid;
import dev.heimdall.vehicleauth.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService vehicleService;

    @PostMapping
    public ResponseEntity<VehicleProvisioningResponse> create(
            @Valid @RequestBody CreateVehicleRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(vehicleService.create(request));
    }

    @VehicleAuthenticated
    @PostMapping("/{id}/heartbeat")
    public VehicleResponse heartbeat(@PathVariable UUID id,
            @RequestAttribute(VehicleRequestAuthenticator.IDENTITY) UUID authenticatedVehicleId) {
        VehicleAccessGuard.requireOwner(authenticatedVehicleId, id);
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
