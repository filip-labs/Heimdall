package dev.heimdall.vehicleauth;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class VehicleCredentialService {
    private final SecureRandom random = new SecureRandom();
    private static final String ABSENT_HASH = "0".repeat(64);

    public String generate() {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    public String hash(String credential) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(credential.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public boolean matches(String credential, String storedHash) {
        // Do the same digest/comparison for unknown and legacy vehicles too.
        byte[] expected = HexFormat.of().parseHex(storedHash == null ? ABSENT_HASH : storedHash);
        byte[] supplied = HexFormat.of().parseHex(hash(credential));
        boolean equal = MessageDigest.isEqual(expected, supplied);
        return equal && storedHash != null;
    }
}
