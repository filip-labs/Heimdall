package dev.heimdall.vehicleauth;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/vehicle-auth")
@RequiredArgsConstructor
public class VehicleAuthenticationController {
    private final VehicleAuthenticationService authentication;

    @PostMapping("/token")
    public ResponseEntity<VehicleTokenResponse> token(@Valid @RequestBody AuthenticateVehicleRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(authentication.authenticate(request));
    }
}
