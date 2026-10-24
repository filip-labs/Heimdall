package dev.heimdall.release;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSoftwareReleaseRequest(

        @NotBlank
        @Size(max = 50)
        String version,

        @Size(max = 500)
        String description,

        @NotBlank
        @Size(max = 500)
        String artifactUrl,

        @NotBlank
        @jakarta.validation.constraints.Pattern(regexp = "[0-9a-fA-F]{64}",
                message = "Checksum must be exactly 64 hexadecimal SHA-256 characters")
        String checksum
) {
}
