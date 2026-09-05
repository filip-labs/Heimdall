package dev.heimdall.rollout;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rollouts")
public class RolloutController {

    private final RolloutService rolloutService;

    public RolloutController(RolloutService rolloutService) {
        this.rolloutService = rolloutService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RolloutResponse create(
            @Valid @RequestBody CreateRolloutRequest request
    ) {
        return rolloutService.create(request);
    }

    @GetMapping
    public List<RolloutResponse> getAll() {
        return rolloutService.getAll();
    }

    @GetMapping("/{id}")
    public RolloutResponse getById(@PathVariable UUID id) {
        return rolloutService.getById(id);
    }

    @GetMapping("/{id}/stages")
    public List<RolloutStageResponse> getStages(@PathVariable UUID id) {
        return rolloutService.getStages(id);
    }
}
