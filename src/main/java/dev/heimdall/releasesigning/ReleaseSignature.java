package dev.heimdall.releasesigning;

public record ReleaseSignature(String algorithm, String keyId, String signature) {
}
