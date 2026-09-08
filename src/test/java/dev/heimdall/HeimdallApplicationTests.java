package dev.heimdall;

import dev.heimdall.config.RolloutProperties;
import dev.heimdall.config.VehicleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Testcontainers
class HeimdallApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17");

    @Autowired
    private VehicleProperties vehicleProperties;

    @Autowired
    private RolloutProperties rolloutProperties;

    @Test
    void contextLoads() {
    }

    @Test
    void shouldBindTypedDurationConfigurationProperties() {
        assertEquals(Duration.ofSeconds(30), vehicleProperties.offlineThreshold());
        assertEquals(Duration.ofSeconds(10), vehicleProperties.offlineCheckInterval());
        assertEquals(Duration.ofSeconds(1), rolloutProperties.evaluationInterval());
    }

}
