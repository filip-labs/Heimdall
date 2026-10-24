package dev.heimdall.vehicleauth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import dev.heimdall.config.VehicleTokenProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

@Service
public class VehicleTokenService {
    private static final String ISSUER = "heimdall";
    private final Algorithm algorithm;
    private final com.auth0.jwt.interfaces.JWTVerifier verifier;
    private final Clock clock;
    private final long ttlSeconds;

    public VehicleTokenService(VehicleTokenProperties properties, Clock clock) {
        this.clock = clock;
        this.ttlSeconds = properties.ttl().toSeconds();
        this.algorithm = Algorithm.HMAC256(Base64.getDecoder().decode(properties.secret()));
        var verification = JWT.require(algorithm).withIssuer(ISSUER)
                .withClaimPresence("sub").withClaimPresence("iat").withClaimPresence("exp");
        this.verifier = ((JWTVerifier.BaseVerification) verification).build(clock);
    }

    public VehicleTokenResponse issue(UUID vehicleId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String token = JWT.create().withIssuer(ISSUER).withSubject(vehicleId.toString())
                .withIssuedAt(now).withExpiresAt(now.plusSeconds(ttlSeconds)).sign(algorithm);
        return new VehicleTokenResponse(token, "Bearer", ttlSeconds);
    }

    public UUID verify(String token) {
        try {
            DecodedJWT jwt = verifier.verify(token);
            UUID vehicleId = UUID.fromString(jwt.getSubject());
            if (!vehicleId.toString().equals(jwt.getSubject())
                    || !jwt.getExpiresAtAsInstant().isAfter(jwt.getIssuedAtAsInstant())
                    || jwt.getExpiresAtAsInstant().isAfter(jwt.getIssuedAtAsInstant().plusSeconds(ttlSeconds))) {
                throw new VehicleAuthenticationException();
            }
            return vehicleId;
        } catch (JWTVerificationException | IllegalArgumentException exception) {
            // Never expose parser details, token contents or signature material.
            throw new VehicleAuthenticationException();
        }
    }
}
