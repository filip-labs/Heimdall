package dev.heimdall.releasesigning;

import dev.heimdall.api.BadRequestException;
import dev.heimdall.config.ReleaseSigningProperties;
import dev.heimdall.release.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReleaseSigningTest {
    private static final String CHECKSUM = "ab".repeat(32);
    // PUBLIC TEST KEY: all-zero Ed25519 seed, never deployment trust material.
    private static String testKey() {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(
                "302e020100300506032b657004220420" + "00".repeat(32)));
    }
    private ReleaseSigningService signer() {
        return new ReleaseSigningService(new ReleaseSigningProperties(testKey(), "public-test-only-zero-seed"));
    }

    @Test void matchesTheSharedGoGeneratedVectorIncludingUtf8LengthAndSignature() throws Exception {
        var vector = new tools.jackson.databind.ObjectMapper().readTree(
                Files.readString(Path.of("test-vectors/ota-release-v1.json")));
        String version = vector.get("version").asText();
        byte[] bytes = ReleaseManifest.canonicalBytes(version, vector.get("checksum").asText());
        assertThat(HexFormat.of().formatHex(bytes)).isEqualTo(vector.get("manifestHex").asText());
        var signed = signer().sign(version, CHECKSUM);
        assertThat(signed.signature()).isEqualTo(vector.get("signature").asText());
        var publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(vector.get("publicKey").asText())));
        assertThat(verifies(publicKey, bytes, signed.signature())).isTrue();
    }

    @Test void manifestIsDeterministicNormalizesHexAndBindsVersionAndDigest() {
        byte[] bytes = ReleaseManifest.canonicalBytes("2.0.0", CHECKSUM);
        assertThat(bytes).isEqualTo(ReleaseManifest.canonicalBytes("2.0.0", CHECKSUM.toUpperCase(Locale.ROOT)));
        assertThat(bytes).isNotEqualTo(ReleaseManifest.canonicalBytes("2.0.1", CHECKSUM));
        assertThat(bytes).isNotEqualTo(ReleaseManifest.canonicalBytes("2.0.0", "cd".repeat(32)));
        assertThat(bytes).isEqualTo(ReleaseManifest.canonicalBytes("2.0.0", CHECKSUM));
    }

    @Test void rejectsInvalidDigestsAndVersionBounds() {
        for (String digest : List.of("", "abc", "a".repeat(63), "a".repeat(65), "g".repeat(64), " " + CHECKSUM)) {
            assertThatThrownBy(() -> signer().sign("2.0.0", digest)).isInstanceOf(BadRequestException.class);
        }
        for (String version : List.of("", " ", "v".repeat(51), "\uD800")) {
            assertThatThrownBy(() -> ReleaseManifest.canonicalBytes(version, CHECKSUM)).isInstanceOf(BadRequestException.class);
        }
    }

    @Test void signsWithEd25519AndRejectsWrongKeyOrModifiedManifest() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var service = new ReleaseSigningService(new ReleaseSigningProperties(
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()), "unit-key"));
        var signed = service.sign("2.0.0", CHECKSUM);
        assertThat(signed.algorithm()).isEqualTo("Ed25519");
        assertThat(signed.keyId()).isEqualTo("unit-key");
        assertThat(signed.signature()).matches("[A-Za-z0-9_-]{86}");
        assertThat(Base64.getUrlDecoder().decode(signed.signature())).hasSize(64);
        byte[] manifest = ReleaseManifest.canonicalBytes("2.0.0", CHECKSUM);
        assertThat(verifies(pair.getPublic(), manifest, signed.signature())).isTrue();
        assertThat(verifies(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic(), manifest, signed.signature())).isFalse();
        assertThat(verifies(pair.getPublic(), ReleaseManifest.canonicalBytes("2.0.1", CHECKSUM), signed.signature())).isFalse();
        assertThat(verifies(pair.getPublic(), ReleaseManifest.canonicalBytes("2.0.0", "cd".repeat(32)), signed.signature())).isFalse();
    }

    @Test void failsClosedOnMissingInvalidAndNonEd25519SigningConfigurationWithoutEchoingKeys() throws Exception {
        assertThatThrownBy(() -> new ReleaseSigningProperties("", "key")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReleaseSigningProperties(testKey(), " ")).isInstanceOf(IllegalArgumentException.class);
        for (String key : List.of("not-base64-secret!", Base64.getEncoder().encodeToString(new byte[32]),
                Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").generateKeyPair().getPrivate().getEncoded()))) {
            assertThatThrownBy(() -> new ReleaseSigningService(new ReleaseSigningProperties(key, "key")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(key);
        }
        assertThat(new ReleaseSigningProperties(testKey(), "key").toString()).doesNotContain(testKey());
    }

    @Test void signingFailureOrInvalidChecksumNeverSavesANewUnsignedRelease() {
        var repository = mock(SoftwareReleaseRepository.class);
        var signing = mock(ReleaseSigningService.class);
        var service = new SoftwareReleaseService(repository, signing);
        when(signing.sign("2.0.0", CHECKSUM)).thenThrow(new IllegalStateException("Release signing failed"));
        assertThatThrownBy(() -> service.create(new CreateSoftwareReleaseRequest("2.0.0", "desc", "https://example.com/fw", CHECKSUM)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.create(new CreateSoftwareReleaseRequest("2.0.1", "desc", "https://example.com/fw", "abc")))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    private boolean verifies(PublicKey key, byte[] manifest, String encoded) throws Exception {
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(manifest);
        return verifier.verify(Base64.getUrlDecoder().decode(encoded));
    }
}
