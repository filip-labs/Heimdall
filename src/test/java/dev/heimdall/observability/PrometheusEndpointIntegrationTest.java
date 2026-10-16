package dev.heimdall.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@AutoConfigureMetrics()
@Testcontainers
class PrometheusEndpointIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17");

    @Autowired
    private RestTestClient restClient;

    @Test
    void shouldExposePrometheusMetrics() {
        restClient.get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    assert body != null;
                    if (!body.contains("# HELP") ||
                            !body.contains("jvm_memory_used_bytes") ||
                            !body.contains("heimdall_vehicles") ||
                            !body.contains("heimdall_deployments") ||
                            !body.contains("heimdall_rollouts") ||
                            !body.contains("heimdall_ota_success_total") ||
                            !body.contains("heimdall_ota_failure_total") ||
                            !body.contains("heimdall_ota_duration_seconds_count")) {
                        throw new AssertionError(
                                "Prometheus endpoint did not expose expected metrics"
                        );
                    }
                });
    }
}
