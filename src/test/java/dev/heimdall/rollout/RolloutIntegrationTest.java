package dev.heimdall.rollout;

import dev.heimdall.deployment.DeploymentResponse;
import dev.heimdall.deployment.DeploymentStatus;
import dev.heimdall.release.SoftwareReleaseResponse;
import dev.heimdall.vehicle.VehicleResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "heimdall.rollout.evaluation-interval-ms=3600000"
)
@AutoConfigureRestTestClient
@Testcontainers
class RolloutIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17");

    @Autowired
    private RestTestClient restClient;

    @Autowired
    private RolloutEvaluator rolloutEvaluator;

    @Autowired
    private RolloutTargetRepository rolloutTargetRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    rollout,
                    deployment_event,
                    deployment,
                    software_release,
                    vehicle
                CASCADE
                """);
    }

    @Test
    void shouldSnapshotTargetsAtRolloutCreation() {
        SoftwareReleaseResponse release = createRelease("2.10.0");
        createVehicles("7FC10000000000", 10, "1.0.0");

        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(20, 50, 100),
                BigDecimal.valueOf(5.0)
        );

        createVehicle("7FC10000000009999", "1.0.0");

        assertEquals(10, rolloutTargetRepository.countByRollout_Id(rollout.id()));
    }

    @Test
    void shouldCreateCumulativeStageSizesAndIncrementalDeployments() {
        SoftwareReleaseResponse release = createRelease("2.11.0");
        createVehicles("7FC11000000000", 10, "1.0.0");

        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(20, 50, 100),
                BigDecimal.valueOf(5.0)
        );

        List<RolloutStageResponse> stages = getStages(rollout.id());

        assertEquals(List.of(2, 5, 10), stages.stream()
                .map(RolloutStageResponse::targetVehicleCount)
                .toList());
        assertEquals(2, stages.get(0).deploymentCount());
        assertEquals(0, stages.get(1).deploymentCount());
        assertEquals(0, stages.get(2).deploymentCount());

        completePendingDeployments(release.id(), 2, DeploymentStatus.INSTALLED);
        rolloutEvaluator.evaluateRollout(rollout.id());
        stages = getStages(rollout.id());

        assertEquals(RolloutStageStatus.COMPLETED, stages.get(0).status());
        assertEquals(RolloutStageStatus.RUNNING, stages.get(1).status());
        assertEquals(3, stages.get(1).deploymentCount());

        completePendingDeployments(release.id(), 3, DeploymentStatus.INSTALLED);
        rolloutEvaluator.evaluateRollout(rollout.id());
        stages = getStages(rollout.id());

        assertEquals(RolloutStageStatus.COMPLETED, stages.get(1).status());
        assertEquals(RolloutStageStatus.RUNNING, stages.get(2).status());
        assertEquals(5, stages.get(2).deploymentCount());
    }

    @Test
    void shouldProgressToCompletedAndDeployEachTargetOnce() {
        SoftwareReleaseResponse release = createRelease("2.12.0");
        createVehicles("7FC12000000000", 10, "1.0.0");

        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(20, 50, 100),
                BigDecimal.valueOf(5.0)
        );

        completePendingDeployments(release.id(), 2, DeploymentStatus.INSTALLED);
        rolloutEvaluator.evaluateRollout(rollout.id());
        completePendingDeployments(release.id(), 3, DeploymentStatus.INSTALLED);
        rolloutEvaluator.evaluateRollout(rollout.id());
        completePendingDeployments(release.id(), 5, DeploymentStatus.INSTALLED);
        rolloutEvaluator.evaluateRollout(rollout.id());

        RolloutResponse completed = getRollout(rollout.id());
        List<DeploymentResponse> deployments = deploymentsForRelease(release.id());

        assertEquals(RolloutStatus.COMPLETED, completed.status());
        assertEquals(10, deployments.size());
        assertEquals(10, deployments.stream()
                .collect(Collectors.groupingBy(
                        DeploymentResponse::vehicleId,
                        Collectors.counting()
                ))
                .size());
    }

    @Test
    void shouldAllowFailureRateEqualToThreshold() {
        SoftwareReleaseResponse release = createRelease("2.13.0");
        createVehicles("7FC13000000000", 10, "1.0.0");

        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(50, 100),
                BigDecimal.valueOf(20.0)
        );

        completePendingDeployments(release.id(), 4, DeploymentStatus.INSTALLED);
        completePendingDeployments(release.id(), 1, DeploymentStatus.FAILED);
        rolloutEvaluator.evaluateRollout(rollout.id());

        List<RolloutStageResponse> stages = getStages(rollout.id());

        assertEquals(RolloutStatus.RUNNING, getRollout(rollout.id()).status());
        assertEquals(RolloutStageStatus.COMPLETED, stages.get(0).status());
        assertEquals(RolloutStageStatus.RUNNING, stages.get(1).status());
        assertEquals(5, stages.get(1).deploymentCount());
    }

    @Test
    void shouldPauseWhenFailureRateExceedsThreshold() {
        SoftwareReleaseResponse release = createRelease("2.14.0");
        createVehicles("7FC14000000000", 10, "1.0.0");

        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(50, 100),
                BigDecimal.valueOf(20.0)
        );

        completePendingDeployments(release.id(), 3, DeploymentStatus.INSTALLED);
        completePendingDeployments(release.id(), 2, DeploymentStatus.FAILED);
        rolloutEvaluator.evaluateRollout(rollout.id());

        List<RolloutStageResponse> stages = getStages(rollout.id());

        assertEquals(RolloutStatus.PAUSED, getRollout(rollout.id()).status());
        assertEquals(RolloutStageStatus.FAILED, stages.get(0).status());
        assertEquals(RolloutStageStatus.PENDING, stages.get(1).status());
        assertEquals(0, stages.get(1).deploymentCount());
    }

    @Test
    void shouldRejectResumeWhenCurrentStageFailedHealthGate() {
        SoftwareReleaseResponse release = createRelease("2.14.1");
        createVehicles("7FC14100000000", 10, "1.0.0");

        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(50, 100),
                BigDecimal.valueOf(20.0)
        );

        completePendingDeployments(release.id(), 3, DeploymentStatus.INSTALLED);
        completePendingDeployments(release.id(), 2, DeploymentStatus.FAILED);
        rolloutEvaluator.evaluateRollout(rollout.id());

        List<RolloutStageResponse> stages = getStages(rollout.id());
        assertEquals(RolloutStatus.PAUSED, getRollout(rollout.id()).status());
        assertEquals(RolloutStageStatus.FAILED, stages.get(0).status());
        assertEquals(RolloutStageStatus.PENDING, stages.get(1).status());

        assertControlConflict(rollout.id(), "resume");

        stages = getStages(rollout.id());
        assertEquals(RolloutStatus.PAUSED, getRollout(rollout.id()).status());
        assertEquals(RolloutStageStatus.FAILED, stages.get(0).status());
        assertEquals(RolloutStageStatus.PENDING, stages.get(1).status());
        assertEquals(0, stages.get(1).deploymentCount());

        rolloutEvaluator.evaluateRollout(rollout.id());

        stages = getStages(rollout.id());
        assertEquals(RolloutStatus.PAUSED, getRollout(rollout.id()).status());
        assertEquals(RolloutStageStatus.FAILED, stages.get(0).status());
        assertEquals(RolloutStageStatus.PENDING, stages.get(1).status());
        assertEquals(0, stages.get(1).deploymentCount());
    }

    @Test
    void shouldRejectRolloutWhenTargetVehicleHasActiveDeployment() {
        VehicleResponse vehicle = createVehicle("7FC15000000000000", "1.0.0");
        SoftwareReleaseResponse manualRelease = createRelease("2.15.0");
        SoftwareReleaseResponse rolloutRelease = createRelease("2.15.1");

        createDeployment(vehicle.id(), manualRelease.id());

        restClient.post()
                .uri("/api/v1/rollouts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "releaseId", rolloutRelease.id(),
                        "stages", List.of(100),
                        "failureThresholdPercent", 5.0
                ))
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void shouldPauseRunningRollout() {
        SoftwareReleaseResponse release = createRelease("2.16.0");
        createVehicles("7FC16000000000", 10, "1.0.0");
        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(50, 100),
                BigDecimal.valueOf(5.0)
        );

        RolloutResponse paused = controlRollout(rollout.id(), "pause");

        assertEquals(RolloutStatus.PAUSED, paused.status());
        assertEquals(RolloutStatus.PAUSED, getRollout(rollout.id()).status());
    }

    @Test
    void shouldPauseRolloutIdempotently() {
        SoftwareReleaseResponse release = createRelease("2.17.0");
        createVehicles("7FC17000000000", 10, "1.0.0");
        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(50, 100),
                BigDecimal.valueOf(5.0)
        );

        controlRollout(rollout.id(), "pause");
        RolloutResponse pausedAgain = controlRollout(rollout.id(), "pause");

        assertEquals(RolloutStatus.PAUSED, pausedAgain.status());
        assertEquals(5, getStages(rollout.id()).getFirst().deploymentCount());
    }

    @Test
    void shouldResumePausedRollout() {
        SoftwareReleaseResponse release = createRelease("2.18.0");
        createVehicles("7FC18000000000", 10, "1.0.0");
        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(50, 100),
                BigDecimal.valueOf(5.0)
        );
        controlRollout(rollout.id(), "pause");

        RolloutResponse resumed = controlRollout(rollout.id(), "resume");

        assertEquals(RolloutStatus.RUNNING, resumed.status());
        assertEquals(RolloutStatus.RUNNING, getRollout(rollout.id()).status());
    }

    @Test
    void shouldResumeRunningRolloutIdempotentlyWithoutDuplicateDeployments() {
        SoftwareReleaseResponse release = createRelease("2.19.0");
        createVehicles("7FC19000000000", 10, "1.0.0");
        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(50, 100),
                BigDecimal.valueOf(5.0)
        );

        RolloutResponse resumed = controlRollout(rollout.id(), "resume");

        assertEquals(RolloutStatus.RUNNING, resumed.status());
        assertEquals(5, getStages(rollout.id()).getFirst().deploymentCount());
        assertEquals(5, deploymentsForRelease(release.id()).size());
    }

    @Test
    void shouldCancelRunningRollout() {
        SoftwareReleaseResponse release = createRelease("2.20.0");
        createVehicles("7FC20000000000", 10, "1.0.0");
        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(50, 100),
                BigDecimal.valueOf(5.0)
        );

        RolloutResponse cancelled = controlRollout(rollout.id(), "cancel");

        assertEquals(RolloutStatus.CANCELLED, cancelled.status());
        assertEquals(RolloutStatus.CANCELLED, getRollout(rollout.id()).status());
    }

    @Test
    void shouldCancelPausedRollout() {
        SoftwareReleaseResponse release = createRelease("2.21.0");
        createVehicles("7FC21000000000", 10, "1.0.0");
        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(50, 100),
                BigDecimal.valueOf(5.0)
        );
        controlRollout(rollout.id(), "pause");

        RolloutResponse cancelled = controlRollout(rollout.id(), "cancel");

        assertEquals(RolloutStatus.CANCELLED, cancelled.status());
        assertEquals(RolloutStatus.CANCELLED, getRollout(rollout.id()).status());
    }

    @Test
    void shouldCancelRolloutIdempotently() {
        SoftwareReleaseResponse release = createRelease("2.22.0");
        createVehicles("7FC22000000000", 10, "1.0.0");
        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(50, 100),
                BigDecimal.valueOf(5.0)
        );

        controlRollout(rollout.id(), "cancel");
        RolloutResponse cancelledAgain = controlRollout(rollout.id(), "cancel");

        assertEquals(RolloutStatus.CANCELLED, cancelledAgain.status());
        assertEquals(5, deploymentsForRelease(release.id()).size());
    }

    @Test
    void shouldRejectCompletedRolloutControlActions() {
        SoftwareReleaseResponse release = createRelease("2.23.0");
        createVehicles("7FC23000000000", 5, "1.0.0");
        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(100),
                BigDecimal.valueOf(5.0)
        );
        completePendingDeployments(release.id(), 5, DeploymentStatus.INSTALLED);
        rolloutEvaluator.evaluateRollout(rollout.id());

        assertEquals(RolloutStatus.COMPLETED, getRollout(rollout.id()).status());
        assertControlConflict(rollout.id(), "pause");
        assertControlConflict(rollout.id(), "resume");
        assertControlConflict(rollout.id(), "cancel");
    }

    @Test
    void shouldRejectCancelledRolloutPauseAndResume() {
        SoftwareReleaseResponse release = createRelease("2.24.0");
        createVehicles("7FC24000000000", 10, "1.0.0");
        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(50, 100),
                BigDecimal.valueOf(5.0)
        );
        controlRollout(rollout.id(), "cancel");

        assertControlConflict(rollout.id(), "pause");
        assertControlConflict(rollout.id(), "resume");
    }

    @Test
    void shouldReturnNotFoundForMissingRolloutControlAction() {
        restClient.post()
                .uri("/api/v1/rollouts/" + UUID.randomUUID() + "/pause")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void shouldNotProgressPausedRolloutWhenCurrentStageDeploymentsFinish() {
        SoftwareReleaseResponse release = createRelease("2.25.0");
        createVehicles("7FC25000000000", 10, "1.0.0");
        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(50, 100),
                BigDecimal.valueOf(5.0)
        );

        controlRollout(rollout.id(), "pause");
        completePendingDeployments(release.id(), 5, DeploymentStatus.INSTALLED);
        rolloutEvaluator.evaluateRollout(rollout.id());

        List<RolloutStageResponse> stages = getStages(rollout.id());
        assertEquals(RolloutStatus.PAUSED, getRollout(rollout.id()).status());
        assertEquals(RolloutStageStatus.RUNNING, stages.get(0).status());
        assertEquals(RolloutStageStatus.PENDING, stages.get(1).status());
        assertEquals(0, stages.get(1).deploymentCount());
        assertEquals(5, deploymentsForRelease(release.id()).size());
    }

    @Test
    void shouldResumeAndContinuePausedRolloutWithoutDuplicateDeployments() {
        SoftwareReleaseResponse release = createRelease("2.26.0");
        createVehicles("7FC26000000000", 10, "1.0.0");
        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(50, 100),
                BigDecimal.valueOf(5.0)
        );

        controlRollout(rollout.id(), "pause");
        completePendingDeployments(release.id(), 5, DeploymentStatus.INSTALLED);
        controlRollout(rollout.id(), "resume");
        rolloutEvaluator.evaluateRollout(rollout.id());
        rolloutEvaluator.evaluateRollout(rollout.id());

        List<RolloutStageResponse> stages = getStages(rollout.id());
        assertEquals(RolloutStatus.RUNNING, getRollout(rollout.id()).status());
        assertEquals(RolloutStageStatus.COMPLETED, stages.get(0).status());
        assertEquals(RolloutStageStatus.RUNNING, stages.get(1).status());
        assertEquals(5, stages.get(1).deploymentCount());
        assertEquals(10, deploymentsForRelease(release.id()).size());
    }

    @Test
    void shouldNotProgressCancelledRolloutWhenCurrentStageDeploymentsFinish() {
        SoftwareReleaseResponse release = createRelease("2.27.0");
        createVehicles("7FC27000000000", 10, "1.0.0");
        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(50, 100),
                BigDecimal.valueOf(5.0)
        );

        controlRollout(rollout.id(), "cancel");
        completePendingDeployments(release.id(), 5, DeploymentStatus.INSTALLED);
        rolloutEvaluator.evaluateRollout(rollout.id());

        List<RolloutStageResponse> stages = getStages(rollout.id());
        assertEquals(RolloutStatus.CANCELLED, getRollout(rollout.id()).status());
        assertEquals(RolloutStageStatus.RUNNING, stages.get(0).status());
        assertEquals(RolloutStageStatus.PENDING, stages.get(1).status());
        assertEquals(0, stages.get(1).deploymentCount());
        assertEquals(5, deploymentsForRelease(release.id()).size());
    }

    @Test
    void shouldLeaveExistingDeploymentUntouchedWhenPaused() {
        SoftwareReleaseResponse release = createRelease("2.28.0");
        createVehicles("7FC28000000000", 10, "1.0.0");
        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(50, 100),
                BigDecimal.valueOf(5.0)
        );
        UUID deploymentId = deploymentsForRelease(release.id()).getFirst().id();

        controlRollout(rollout.id(), "pause");

        assertEquals(
                DeploymentStatus.PENDING,
                getDeployment(deploymentId).status()
        );
    }

    @Test
    void shouldLeaveExistingDeploymentUntouchedWhenCancelled() {
        SoftwareReleaseResponse release = createRelease("2.29.0");
        createVehicles("7FC29000000000", 10, "1.0.0");
        RolloutResponse rollout = createRollout(
                release.id(),
                List.of(50, 100),
                BigDecimal.valueOf(5.0)
        );
        UUID deploymentId = deploymentsForRelease(release.id()).getFirst().id();

        controlRollout(rollout.id(), "cancel");

        assertEquals(
                DeploymentStatus.PENDING,
                getDeployment(deploymentId).status()
        );
    }

    private List<VehicleResponse> createVehicles(
            String vinPrefix,
            int count,
            String softwareVersion
    ) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> createVehicle(
                        vinPrefix + String.format("%03d", index),
                        softwareVersion
                ))
                .toList();
    }

    private VehicleResponse createVehicle(
            String vin,
            String softwareVersion
    ) {
        return restClient.post()
                .uri("/api/v1/vehicles")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "vin", vin,
                        "softwareVersion", softwareVersion
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(VehicleResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private SoftwareReleaseResponse createRelease(String version) {
        return restClient.post()
                .uri("/api/v1/releases")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "version", version,
                        "description", "Rollout integration test release",
                        "artifactUrl", "https://updates.heimdall.dev/" + version + ".bin",
                        "checksum", "abc123"
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(SoftwareReleaseResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private RolloutResponse createRollout(
            UUID releaseId,
            List<Integer> stages,
            BigDecimal failureThresholdPercent
    ) {
        return restClient.post()
                .uri("/api/v1/rollouts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "releaseId", releaseId,
                        "stages", stages,
                        "failureThresholdPercent", failureThresholdPercent
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(RolloutResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private DeploymentResponse createDeployment(UUID vehicleId, UUID releaseId) {
        return restClient.post()
                .uri("/api/v1/deployments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "vehicleId", vehicleId,
                        "releaseId", releaseId
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(DeploymentResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private RolloutResponse getRollout(UUID rolloutId) {
        return restClient.get()
                .uri("/api/v1/rollouts/" + rolloutId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(RolloutResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private RolloutResponse controlRollout(UUID rolloutId, String action) {
        return restClient.post()
                .uri("/api/v1/rollouts/" + rolloutId + "/" + action)
                .exchange()
                .expectStatus().isOk()
                .expectBody(RolloutResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private void assertControlConflict(UUID rolloutId, String action) {
        restClient.post()
                .uri("/api/v1/rollouts/" + rolloutId + "/" + action)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    private DeploymentResponse getDeployment(UUID deploymentId) {
        return restClient.get()
                .uri("/api/v1/deployments/" + deploymentId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DeploymentResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private List<RolloutStageResponse> getStages(UUID rolloutId) {
        RolloutStageResponse[] stages = restClient.get()
                .uri("/api/v1/rollouts/" + rolloutId + "/stages")
                .exchange()
                .expectStatus().isOk()
                .expectBody(RolloutStageResponse[].class)
                .returnResult()
                .getResponseBody();

        return Arrays.asList(stages);
    }

    private void completePendingDeployments(
            UUID releaseId,
            int count,
            DeploymentStatus terminalStatus
    ) {
        deploymentsForRelease(releaseId)
                .stream()
                .filter(deployment -> deployment.status() == DeploymentStatus.PENDING)
                .limit(count)
                .forEach(deployment -> completeDeployment(deployment.id(), terminalStatus));
    }

    private List<DeploymentResponse> deploymentsForRelease(UUID releaseId) {
        DeploymentResponse[] deployments = restClient.get()
                .uri("/api/v1/deployments")
                .exchange()
                .expectStatus().isOk()
                .expectBody(DeploymentResponse[].class)
                .returnResult()
                .getResponseBody();

        return Arrays.stream(deployments)
                .filter(deployment -> deployment.releaseId().equals(releaseId))
                .toList();
    }

    private void completeDeployment(
            UUID deploymentId,
            DeploymentStatus terminalStatus
    ) {
        updateStatus(deploymentId, DeploymentStatus.DOWNLOADING, null);
        if (terminalStatus == DeploymentStatus.FAILED) {
            updateStatus(deploymentId, DeploymentStatus.FAILED, "Rollout test failure");
            return;
        }
        updateStatus(deploymentId, DeploymentStatus.DOWNLOADED, null);
        updateStatus(deploymentId, DeploymentStatus.INSTALLING, null);
        updateStatus(deploymentId, DeploymentStatus.INSTALLED, null);
    }

    private void updateStatus(
            UUID deploymentId,
            DeploymentStatus status,
            String failureReason
    ) {
        Map<String, Object> body = failureReason == null
                ? Map.of("status", status)
                : Map.of(
                        "status", status,
                        "failureReason", failureReason
                );

        restClient.patch()
                .uri("/api/v1/deployments/" + deploymentId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isOk();
    }
}
