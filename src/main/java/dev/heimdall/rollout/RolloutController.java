package dev.heimdall.rollout;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rollouts")
@RequiredArgsConstructor
public class RolloutController {

    private final RolloutService rolloutService;
    private final RolloutControlService rolloutControlService;

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

    @PostMapping("/{id}/pause")
    public RolloutResponse pause(@PathVariable UUID id) {
        return rolloutControlService.pause(id);
    }

    @PostMapping("/{id}/resume")
    public RolloutResponse resume(@PathVariable UUID id) {
        return rolloutControlService.resume(id);
    }

    @PostMapping("/{id}/cancel")
    public RolloutResponse cancel(@PathVariable UUID id) {
        return rolloutControlService.cancel(id);
    }
}
