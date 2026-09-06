package dev.heimdall.rollout;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RolloutStagePlanner {

    public List<RolloutStage> planStages(
            Rollout rollout,
            List<Integer> requestedStages,
            int totalVehicles
    ) {
        List<RolloutStage> stages = new ArrayList<>();
        for (int i = 0; i < requestedStages.size(); i++) {
            int percentage = requestedStages.get(i);
            int targetVehicleCount = i == requestedStages.size() - 1
                    ? totalVehicles
                    : (int) Math.ceil(totalVehicles * percentage / 100.0);
            stages.add(new RolloutStage(
                    rollout,
                    i,
                    percentage,
                    targetVehicleCount,
                    i == 0 ? RolloutStageStatus.RUNNING : RolloutStageStatus.PENDING
            ));
        }
        return stages;
    }
}
