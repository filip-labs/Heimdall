package dev.heimdall.vehicleauth;

import dev.heimdall.vehicle.Vehicle;
import dev.heimdall.vehicle.VehicleRepository;
import dev.heimdall.vehicle.VehicleResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Testcontainers
class VehicleAuthenticationIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired private RestTestClient restClient;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private VehicleRepository vehicles;
    @Autowired private VehicleCredentialService credentials;
    @Autowired private VehicleTokenService tokens;

    @Test
    void provisioningReturnsSecretOnceStoresOnlyVerifierAndPreservesVehicleFields() {
        Map<?, ?> provisioned = provision("7FC11000000000001");
        UUID id = UUID.fromString((String) provisioned.get("id"));
        String credential = (String) provisioned.get("credential");
        assertThat(credential).matches("[A-Za-z0-9_-]{43}");
        assertThat(provisioned.get("vin")).isEqualTo("7FC11000000000001");
        assertThat(provisioned.get("softwareVersion")).isEqualTo("1.0.0");
        String stored = jdbc.queryForObject("SELECT credential_hash FROM vehicle WHERE id = ?", String.class, id);
        assertThat(stored).isEqualTo(credentials.hash(credential)).isNotEqualTo(credential);
        // PostgreSQL row serialization also catches accidental additional plaintext columns.
        assertThat(jdbc.queryForObject("SELECT row_to_json(v)::text FROM vehicle v WHERE id = ?", String.class, id))
                .doesNotContain(credential);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE payload::text LIKE ? OR payload::text LIKE ?",
                Integer.class, "%" + credential + "%", "%" + stored + "%")).isZero();
        for (String uri : new String[]{"/api/v1/vehicles/" + id,
                "/api/v1/vehicles/by-vin/7FC11000000000001", "/api/v1/vehicles"}) {
            restClient.get().uri(uri).exchange().expectStatus().isOk().expectBody(String.class)
                    .value(body -> assertThat(body).doesNotContain(credential, "credential", stored));
        }
        restClient.post().uri("/api/v1/vehicles/" + id + "/heartbeat").exchange()
                .expectStatus().isOk().expectBody(String.class)
                .value(body -> assertThat(body).doesNotContain(credential, "credential", stored));
        restClient.post().uri("/api/v1/vehicles").body(Map.of("vin", "7FC11000000000001", "softwareVersion", "2.0.0"))
                .exchange().expectStatus().isEqualTo(409).expectBody(String.class)
                .value(body -> assertThat(body).doesNotContain(credential, "credential"));
        assertThat(jdbc.queryForObject("SELECT credential_hash FROM vehicle WHERE id = ?", String.class, id))
                .isEqualTo(stored);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle WHERE vin = ?", Integer.class, "7FC11000000000001"))
                .isEqualTo(1);
    }

    @Test
    void authenticatesOnlyTheMatchingVehicleAndReturnsEquivalentFailures() {
        var first = provision("7FC11000000000002");
        var second = provision("7FC11000000000003");
        UUID firstId = UUID.fromString((String) first.get("id"));
        String credential = (String) first.get("credential");
        var response = restClient.post().uri("/api/v1/vehicle-auth/token")
                .body(new AuthenticateVehicleRequest(firstId, credential)).exchange()
                .expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody(VehicleTokenResponse.class).returnResult().getResponseBody();
        assertThat(response).isNotNull();
        assertThat(response.expiresIn()).isEqualTo(300);
        assertThat(tokens.verify(response.accessToken())).isEqualTo(firstId);
        ProblemDetail wrong = failure(firstId, credentials.generate());
        ProblemDetail unknown = failure(UUID.randomUUID(), credential);
        ProblemDetail crossed = failure(UUID.fromString((String) second.get("id")), credential);
        assertThat(unknown.getDetail()).isEqualTo(wrong.getDetail()).isEqualTo(crossed.getDetail());
        assertThat(unknown.getTitle()).isEqualTo(wrong.getTitle());
        assertThat(unknown.getType()).isEqualTo(wrong.getType());
        assertThat(wrong.getDetail()).isEqualTo("Vehicle authentication failed");
    }

    @Test
    void migrationUpgradesExistingDatabaseWithoutInventingCredentials() {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var oldSchema = org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .schemas("auth_upgrade").defaultSchema("auth_upgrade").target("10").load();
        oldSchema.migrate();
        var database = new JdbcTemplate(dataSource);
        UUID id = UUID.randomUUID();
        database.update("""
                INSERT INTO auth_upgrade.vehicle
                  (id, vin, software_version, connectivity_status, created_at, updated_at)
                VALUES (?, '7FC11000000000005', '1.0.0', 'OFFLINE', now(), now())
                """, id);
        org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .schemas("auth_upgrade").defaultSchema("auth_upgrade").load().migrate();
        assertThat(database.queryForObject("SELECT credential_hash FROM auth_upgrade.vehicle WHERE id = ?",
                String.class, id)).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> database.update(
                "UPDATE auth_upgrade.vehicle SET credential_hash = 'plaintext' WHERE id = ?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void legacyVehicleWithNullVerifierCannotAuthenticate() {
        Vehicle legacy = vehicles.save(new Vehicle("7FC11000000000004", "1.0.0"));
        assertThat(legacy.getCredentialHash()).isNull();
        failure(legacy.getId(), credentials.generate());
        restClient.get().uri("/api/v1/vehicles/" + legacy.getId()).exchange().expectStatus().isOk()
                .expectBody(VehicleResponse.class).value(body -> assertThat(body.id()).isEqualTo(legacy.getId()));
    }

    @Test
    void validatesAuthRequestsAndRedactsSecretsInStringRepresentations() {
        String credential = credentials.generate();
        restClient.post().uri("/api/v1/vehicle-auth/token").body(Map.of("credential", credential))
                .exchange().expectStatus().isBadRequest().expectBody(String.class)
                .value(body -> assertThat(body).doesNotContain(credential));
        restClient.post().uri("/api/v1/vehicle-auth/token")
                .body(new AuthenticateVehicleRequest(UUID.randomUUID(), " "))
                .exchange().expectStatus().isBadRequest();
        assertThat(new AuthenticateVehicleRequest(UUID.randomUUID(), credential).toString()).doesNotContain(credential);
        assertThat(new VehicleTokenResponse(credential, "Bearer", 300).toString()).doesNotContain(credential);
        assertThat(new dev.heimdall.vehicle.VehicleProvisioningResponse(null, credential).toString())
                .doesNotContain(credential);
    }

    private Map<?, ?> provision(String vin) {
        return restClient.post().uri("/api/v1/vehicles").body(Map.of("vin", vin, "softwareVersion", "1.0.0"))
                .exchange().expectStatus().isCreated().expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    private ProblemDetail failure(UUID id, String credential) {
        return restClient.post().uri("/api/v1/vehicle-auth/token")
                .body(new AuthenticateVehicleRequest(id, credential)).exchange()
                .expectStatus().isUnauthorized().expectBody(ProblemDetail.class).returnResult().getResponseBody();
    }
}
