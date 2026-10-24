package dev.heimdall.releasesigning;

import dev.heimdall.deployment.*;
import dev.heimdall.release.*;
import dev.heimdall.vehicle.*;
import dev.heimdall.vehicleauth.VehicleTokenService;
import dev.heimdall.config.ReleaseSigningProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class ReleaseSigningIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");
    @Autowired RestTestClient client;
    @Autowired SoftwareReleaseRepository repository;
    @Autowired SoftwareReleaseService releases;
    @Autowired VehicleService vehicles;
    @Autowired VehicleTokenService tokens;
    @Autowired DeploymentService deployments;
    @Autowired ReleaseSigningProperties properties;
    @Autowired ReleaseSigningService signing;

    @Test void apiPersistsNormalizedDigestAndSignatureAndDeploymentCarriesTheSamePublicMetadata() {
        var release = client.post().uri("/api/v1/releases").body(Map.of("version", "signed-api-1", "artifactUrl", "https://example.com/fw",
                "checksum", "AB".repeat(32))).exchange().expectStatus().isCreated().expectBody(SoftwareReleaseResponse.class)
                .returnResult().getResponseBody();
        assertThat(release.checksum()).isEqualTo("ab".repeat(32));
        assertThat(release.signatureAlgorithm()).isEqualTo("Ed25519");
        assertThat(release.signingKeyId()).isEqualTo(properties.keyId());
        assertThat(release.signature()).matches("[A-Za-z0-9_-]{86}");
        var persisted = repository.findById(release.id()).orElseThrow();
        assertThat(persisted.getSignature()).isEqualTo(release.signature());
        assertThat(persisted.getSigningKeyId()).isEqualTo(properties.keyId());
        assertThat(persisted.getChecksum()).isEqualTo(release.checksum());
        for (String uri : List.of("/api/v1/releases/" + release.id(), "/api/v1/releases")) {
            client.get().uri(uri).exchange().expectStatus().isOk().expectBody(String.class)
                    .value(body -> assertThat(body).contains(release.signature()).doesNotContain(properties.privateKey(), "privateKey"));
        }
        var vehicle = vehicles.create(new CreateVehicleRequest("7FC13000000000001", "1.0.0")).vehicle();
        var deployment = deployments.create(new CreateDeploymentRequest(vehicle.id(), release.id()));
        assertThat(deployment.signature()).isEqualTo(release.signature());
        assertThat(deployment.signatureAlgorithm()).isEqualTo("Ed25519");
        assertThat(deployment.signingKeyId()).isEqualTo(release.signingKeyId());
        client.get().uri("/api/v1/vehicles/" + vehicle.id() + "/deployments/active")
                .header("Authorization", "Bearer " + tokens.issue(vehicle.id()).accessToken()).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.signature").isEqualTo(release.signature());
        client.post().uri("/api/v1/releases").body(Map.of("version", "signed-api-1", "artifactUrl", "https://example.com/other", "checksum", "cd".repeat(32)))
                .exchange().expectStatus().isEqualTo(409);
        assertThat(repository.findById(release.id()).orElseThrow().getSignature()).isEqualTo(release.signature());
    }

    @Test void malformedChecksumsReturn400WithoutPersisting() {
        int index = 0;
        for (String digest : List.of("abc", "a".repeat(63), "a".repeat(65), "g".repeat(64))) {
            String version = "invalid-api-" + index++;
            client.post().uri("/api/v1/releases").body(Map.of("version", version, "artifactUrl", "https://example.com/fw", "checksum", digest))
                    .exchange().expectStatus().isBadRequest();
            assertThat(repository.existsByVersion(version)).isFalse();
        }
    }

    @Test void migrationFromV11KeepsUnsignedLegacyRowsAndAcceptsNewSignedRows() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas("signing_upgrade").defaultSchema("signing_upgrade").target("11").load().migrate();
        var jdbc = new JdbcTemplate(source);
        UUID legacy = UUID.randomUUID();
        jdbc.update("INSERT INTO signing_upgrade.software_release (id, version, artifact_url, checksum, created_at) VALUES (?, 'legacy', 'http://localhost/fw', 'abc', now())", legacy);
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas("signing_upgrade").defaultSchema("signing_upgrade").load().migrate();
        var row = jdbc.queryForMap("SELECT * FROM signing_upgrade.software_release WHERE id = ?", legacy);
        assertThat(row.get("checksum")).isEqualTo("abc");
        for (String field : List.of("signature_algorithm", "signing_key_id", "signature")) assertThat(row.get(field)).isNull();
        var signature = signing.sign("new-signed", "ab".repeat(32));
        jdbc.update("INSERT INTO signing_upgrade.software_release (id, version, artifact_url, checksum, created_at, signature_algorithm, signing_key_id, signature) VALUES (?, 'new-signed', 'http://localhost/fw', ?, now(), ?, ?, ?)", UUID.randomUUID(), "ab".repeat(32), signature.algorithm(), signature.keyId(), signature.signature());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM signing_upgrade.software_release", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("UPDATE signing_upgrade.software_release SET signature_algorithm = 'Ed25519' WHERE id = ?", legacy))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
