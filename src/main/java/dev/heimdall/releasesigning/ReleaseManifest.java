package dev.heimdall.releasesigning;

import dev.heimdall.api.BadRequestException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.util.HexFormat;
import java.util.Locale;

public final class ReleaseManifest {
    private static final byte[] PREFIX = "HEIMDALL-OTA-RELEASE-V1\0".getBytes(StandardCharsets.US_ASCII);
    private ReleaseManifest() { }

    public static String normalizeChecksum(String checksum) {
        if (checksum == null || !checksum.matches("[0-9a-fA-F]{64}")) {
            throw new BadRequestException("Checksum must be exactly 64 hexadecimal SHA-256 characters");
        }
        return checksum.toLowerCase(Locale.ROOT);
    }

    public static byte[] canonicalBytes(String version, String checksum) {
        if (version == null || version.isBlank() || version.length() > 50) {
            throw new BadRequestException("Software version must contain 1 to 50 characters");
        }
        byte[] versionBytes;
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(version));
            versionBytes = new byte[encoded.remaining()];
            encoded.get(versionBytes);
        } catch (CharacterCodingException exception) {
            throw new BadRequestException("Software version must be valid Unicode");
        }
        byte[] digest = HexFormat.of().parseHex(normalizeChecksum(checksum));
        return ByteBuffer.allocate(PREFIX.length + 2 + versionBytes.length + digest.length)
                .put(PREFIX).putShort((short) versionBytes.length).put(versionBytes).put(digest).array();
    }
}
