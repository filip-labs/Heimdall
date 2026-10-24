package dev.heimdall.releasesigning;

import dev.heimdall.config.ReleaseSigningProperties;
import org.springframework.stereotype.Service;
import java.security.*;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

@Service
public class ReleaseSigningService {
    private final PrivateKey privateKey;
    private final String keyId;

    public ReleaseSigningService(ReleaseSigningProperties properties) {
        this.keyId = properties.keyId();
        try {
            byte[] der = Base64.getDecoder().decode(properties.privateKey());
            this.privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(privateKey instanceof EdECPrivateKey edKey) || !edKey.getParams().getName().equals("Ed25519")) {
                throw new GeneralSecurityException();
            }
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            // Do not retain parser exceptions, which may describe key material.
            throw new IllegalArgumentException("Release signing private key must be Base64 PKCS#8 DER Ed25519");
        }
    }

    public ReleaseSignature sign(String version, String checksum) {
        byte[] manifest = ReleaseManifest.canonicalBytes(version, checksum);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(manifest);
            return new ReleaseSignature("Ed25519", keyId,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Release signing failed");
        }
    }
}
