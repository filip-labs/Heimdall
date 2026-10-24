package dev.heimdall.vehicleauth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import dev.heimdall.config.VehicleTokenProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class VehicleTokenServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final Duration TTL = Duration.ofMinutes(5);
    private final UUID vehicleId = UUID.randomUUID();

    private VehicleTokenService service(Instant now) {
        return new VehicleTokenService(new VehicleTokenProperties(KEY, TTL), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void issuesAndVerifiesShortLivedVehicleIdentity() {
        var response = service(NOW).issue(vehicleId);
        var jwt = JWT.decode(response.accessToken());
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(300);
        assertThat(jwt.getAlgorithm()).isEqualTo("HS256");
        assertThat(jwt.getIssuer()).isEqualTo("heimdall");
        assertThat(jwt.getSubject()).isEqualTo(vehicleId.toString());
        assertThat(jwt.getIssuedAtAsInstant()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAtAsInstant()).isEqualTo(NOW.plus(TTL));
        assertThat(jwt.getClaims()).containsOnlyKeys("iss", "sub", "iat", "exp");
        assertThat(service(NOW.plusSeconds(299)).verify(response.accessToken())).isEqualTo(vehicleId);
    }

    @Test
    void rejectsExpirationAtBoundaryWithoutSleeping() {
        String token = service(NOW).issue(vehicleId).accessToken();
        assertThatThrownBy(() -> service(NOW.plus(TTL)).verify(token))
                .isInstanceOf(VehicleAuthenticationException.class);
    }

    @Test
    void rejectsTamperedPayloadWrongKeyAndUnsignedTokens() {
        String token = service(NOW).issue(vehicleId).accessToken();
        var parts = token.split("\\.");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8)
                        .replace(vehicleId.toString(), UUID.randomUUID().toString())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "." + parts[2];
        assertRejected(tampered);
        assertRejected(JWT.create().withSubject(vehicleId.toString()).sign(Algorithm.none()));
        assertRejected(JWT.create().withIssuer("heimdall").withSubject(vehicleId.toString())
                .withIssuedAt(NOW).withExpiresAt(NOW.plus(TTL)).sign(Algorithm.HMAC256("a-different-signing-key-with-32-bytes")));
        assertRejected("not-a-token");
    }

    @Test
    void rejectsWrongIssuerMissingClaimsInvalidSubjectFutureIssueAndExcessiveLifetime() {
        Algorithm algorithm = Algorithm.HMAC256(Base64.getDecoder().decode(KEY));
        assertRejected(JWT.create().withIssuer("other").withSubject(vehicleId.toString())
                .withIssuedAt(NOW).withExpiresAt(NOW.plus(TTL)).sign(algorithm));
        assertRejected(JWT.create().withIssuer("heimdall").withSubject(vehicleId.toString()).sign(algorithm));
        assertRejected(JWT.create().withIssuer("heimdall").withSubject("not-a-uuid")
                .withIssuedAt(NOW).withExpiresAt(NOW.plus(TTL)).sign(algorithm));
        assertRejected(JWT.create().withIssuer("heimdall").withSubject(vehicleId.toString())
                .withIssuedAt(NOW.plusSeconds(1)).withExpiresAt(NOW.plus(TTL)).sign(algorithm));
        assertRejected(JWT.create().withIssuer("heimdall").withSubject(vehicleId.toString())
                .withIssuedAt(NOW).withExpiresAt(NOW.plusSeconds(301)).sign(algorithm));
    }

    @Test
    void rejectsUnsafeConfigurationWithoutIncludingSecretInMessages() {
        assertThatThrownBy(() -> new VehicleTokenProperties("", TTL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VehicleTokenProperties("invalid-secret!", TTL))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("invalid-secret!");
        for (Duration ttl : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1),
                Duration.ofMinutes(16), Duration.ofMillis(1500)}) {
            assertThatThrownBy(() -> new VehicleTokenProperties(KEY, ttl)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new VehicleTokenProperties(KEY, TTL).toString()).doesNotContain(KEY);
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> service(NOW).verify(token)).isInstanceOf(VehicleAuthenticationException.class)
                .hasMessage("Vehicle authentication failed");
    }
}
