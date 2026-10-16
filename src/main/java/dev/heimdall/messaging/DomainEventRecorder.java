package dev.heimdall.messaging;

import dev.heimdall.deployment.Deployment;
import dev.heimdall.deployment.DeploymentStatus;
import dev.heimdall.rollout.Rollout;
import dev.heimdall.rollout.RolloutStage;
import dev.heimdall.vehicle.Vehicle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DomainEventRecorder {

    private final OutboxService outboxService;
    public static final String STATUS = "status";
    public static final String ROLLOUT_ID = "rolloutId";


    public void vehicleRegistered(Vehicle vehicle) {
        outboxService.append(
                HeimdallTopics.VEHICLE_EVENTS,
                "vehicle.registered",
                "vehicle",
                vehicle.getId(),
                Map.of(
                        "vehicleId", vehicle.getId(),
                        "vin", vehicle.getVin(),
                        "softwareVersion", vehicle.getSoftwareVersion()
                )
        );
    }

    public void deploymentCreated(Deployment deployment) {
        Map<String, Object> payload = deploymentContext(deployment);
        payload.put(STATUS, deployment.getStatus());
        payload.put(
                "rollbackOfDeploymentId",
                deployment.getRollbackOfDeployment() == null
                        ? null
                        : deployment.getRollbackOfDeployment().getId()
        );
        appendDeployment("deployment.created", deployment, payload);
    }

    public void deploymentStatusChanged(
            Deployment deployment,
            DeploymentStatus fromStatus
    ) {
        Map<String, Object> payload = deploymentContext(deployment);
        payload.put("fromStatus", fromStatus);
        payload.put("toStatus", deployment.getStatus());
        payload.put("failureReason", deployment.getFailureReason());
        appendDeployment("deployment.status.changed", deployment, payload);
    }

    public void rolloutStarted(Rollout rollout) {
        appendRollout("rollout.started", rollout, Map.of(
            ROLLOUT_ID, rollout.getId(),
                "releaseId", rollout.getRelease().getId(),
                "totalVehicles", rollout.getTotalVehicles(),
                "automaticRollbackEnabled", rollout.isAutomaticRollbackEnabled()
        ));
    }

    public void rolloutStageStarted(Rollout rollout, RolloutStage stage) {
        appendRollout("rollout.stage.started", rollout, stagePayload(stage));
    }

    public void rolloutStageCompleted(Rollout rollout, RolloutStage stage) {
        appendRollout("rollout.stage.completed", rollout, stagePayload(stage));
    }

    public void rolloutStageFailed(Rollout rollout, RolloutStage stage) {
        appendRollout("rollout.stage.failed", rollout, stagePayload(stage));
    }

    public void rolloutPaused(Rollout rollout, String reason) {
        appendRollout("rollout.paused", rollout, Map.of(
            ROLLOUT_ID, rollout.getId(),
                "currentStageIndex", rollout.getCurrentStageIndex(),
                "reason", reason
        ));
    }

    public void rolloutResumed(Rollout rollout) {
        appendRollout("rollout.resumed", rollout, rolloutStatePayload(rollout));
    }

    public void rolloutCancelled(Rollout rollout) {
        appendRollout("rollout.cancelled", rollout, rolloutStatePayload(rollout));
    }

    public void rolloutCompleted(Rollout rollout) {
        appendRollout("rollout.completed", rollout, rolloutStatePayload(rollout));
    }

    private void appendDeployment(
            String eventType,
            Deployment deployment,
            Map<String, Object> payload
    ) {
        outboxService.append(
                HeimdallTopics.DEPLOYMENT_EVENTS,
                eventType,
                "deployment",
                deployment.getId(),
                payload
        );
    }

    private void appendRollout(
            String eventType,
            Rollout rollout,
            Map<String, ?> payload
    ) {
        outboxService.append(
                HeimdallTopics.ROLLOUT_EVENTS,
                eventType,
                "rollout",
                rollout.getId(),
                payload
        );
    }

    private Map<String, Object> deploymentContext(Deployment deployment) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deploymentId", deployment.getId());
        payload.put("vehicleId", deployment.getVehicle().getId());
        payload.put("releaseId", deployment.getRelease().getId());
        payload.put(
            ROLLOUT_ID,
                deployment.getRolloutStage() == null
                        ? null
                        : deployment.getRolloutStage().getRollout().getId()
        );
        payload.put(
                "rolloutStageId",
                deployment.getRolloutStage() == null
                        ? null
                        : deployment.getRolloutStage().getId()
        );
        payload.put("sourceSoftwareVersion", deployment.getSourceSoftwareVersion());
        payload.put("targetSoftwareVersion", deployment.getRelease().getVersion());
        return payload;
    }

    private Map<String, Object> stagePayload(RolloutStage stage) {
        return Map.of(
            ROLLOUT_ID, stage.getRollout().getId(),
                "rolloutStageId", stage.getId(),
                "stageIndex", stage.getStageIndex(),
                "targetVehicleCount", stage.getTargetVehicleCount(),
            STATUS, stage.getStatus()
        );
    }

    private Map<String, Object> rolloutStatePayload(Rollout rollout) {
        return Map.of(
            ROLLOUT_ID, rollout.getId(),
            STATUS, rollout.getStatus(),
                "currentStageIndex", rollout.getCurrentStageIndex()
        );
    }
}
