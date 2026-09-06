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
        @Size(max = 128)
        String checksum
) {
}
