package dev.heimdall.messaging;

public final class HeimdallTopics {

    public static final String VEHICLE_EVENTS = "heimdall.vehicle-events";
    public static final String DEPLOYMENT_EVENTS = "heimdall.deployment-events";
    public static final String ROLLOUT_EVENTS = "heimdall.rollout-events";
    public static final String DEPLOYMENT_EVENTS_DLT = DEPLOYMENT_EVENTS + ".DLT";
    public static final String ROLLOUT_EVENTS_DLT = ROLLOUT_EVENTS + ".DLT";

    private HeimdallTopics() {
    }
}
