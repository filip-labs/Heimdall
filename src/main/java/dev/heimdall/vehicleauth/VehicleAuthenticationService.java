package dev.heimdall.vehicleauth;

import dev.heimdall.vehicle.Vehicle;
import dev.heimdall.vehicle.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VehicleAuthenticationService {
    private final VehicleRepository vehicles;
    private final VehicleCredentialService credentials;
    private final VehicleTokenService tokens;

    @Transactional(readOnly = true)
    public VehicleTokenResponse authenticate(AuthenticateVehicleRequest request) {
        String verifier = vehicles.findById(request.vehicleId())
                .map(Vehicle::getCredentialHash).orElse(null);
        if (!credentials.matches(request.credential(), verifier)) {
            throw new VehicleAuthenticationException();
        }
        return tokens.issue(request.vehicleId());
    }
}
