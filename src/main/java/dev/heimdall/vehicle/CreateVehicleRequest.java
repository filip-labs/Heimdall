package dev.heimdall.vehicle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateVehicleRequest(

        @NotBlank
        @Pattern(
                regexp = "^[A-HJ-NPR-Z0-9]{17}$",
                message = "VIN must be exactly 17 valid characters"
        )
        String vin,

        @NotBlank
        @Size(max = 50)
        String softwareVersion
) {
}
