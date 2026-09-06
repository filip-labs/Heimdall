package dev.heimdall.release;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/releases")
public class SoftwareReleaseController {

    private final SoftwareReleaseService service;

    public SoftwareReleaseController(SoftwareReleaseService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SoftwareReleaseResponse create(
            @Valid @RequestBody CreateSoftwareReleaseRequest request
    ) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    public SoftwareReleaseResponse getById(@PathVariable UUID id) {
        return service.getById(id);
    }

    @GetMapping
    public List<SoftwareReleaseResponse> getAll() {
        return service.getAll();
    }
}
