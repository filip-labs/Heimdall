package dev.heimdall.vehicleauth;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThat;

class VehicleCredentialServiceTest {
    private final VehicleCredentialService credentials = new VehicleCredentialService();

    @Test
    void generatesIndependent256BitTransportSafeCredentialsAndOnlyVerifiesTheirDigest() {
        String first = credentials.generate();
        String second = credentials.generate();
        assertThat(first).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(second);
        assertThat(Base64.getUrlDecoder().decode(first)).hasSize(32);
        String hash = credentials.hash(first);
        assertThat(hash).matches("[0-9a-f]{64}").isNotEqualTo(first);
        assertThat(credentials.matches(first, hash)).isTrue();
        assertThat(credentials.matches(second, hash)).isFalse();
        assertThat(credentials.matches(first, null)).isFalse();
        assertThat(credentials.hash("abc")).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
