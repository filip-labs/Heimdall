package main

import (
	"crypto/ed25519"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"strings"
	"unicode"
	"unicode/utf8"
)

type releaseVerifier struct {
	publicKey ed25519.PublicKey
	keyID     string
}

func newReleaseVerifier(encodedKey, keyID string) (*releaseVerifier, error) {
	if !utf8.ValidString(keyID) || javaBlank(keyID) || utf16Units(keyID) > 100 {
		return nil, errors.New("OTA signing key ID is required (maximum 100 UTF-16 units)")
	}
	der, err := base64.StdEncoding.DecodeString(encodedKey)
	if err != nil || len(der) == 0 {
		return nil, errors.New("OTA signing public key must be Base64 SPKI DER Ed25519")
	}
	parsed, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, errors.New("OTA signing public key must be Base64 SPKI DER Ed25519")
	}
	publicKey, ok := parsed.(ed25519.PublicKey)
	if !ok {
		return nil, errors.New("OTA signing public key must be Ed25519")
	}
	return &releaseVerifier{publicKey: publicKey, keyID: keyID}, nil
}

func releaseManifest(version, checksum string) ([]byte, error) {
	if !utf8.ValidString(version) || javaBlank(version) {
		return nil, errors.New("invalid release manifest version")
	}
	// Java's existing @Size(max=50) counts UTF-16 code units, including surrogate pairs.
	units := utf16Units(version)
	if units > 50 {
		return nil, errors.New("invalid release manifest version")
	}
	if len(checksum) != 64 {
		return nil, errors.New("invalid release manifest SHA-256 digest")
	}
	digest, err := hex.DecodeString(strings.ToLower(checksum))
	if err != nil || len(digest) != 32 {
		return nil, errors.New("invalid release manifest SHA-256 digest")
	}
	manifest := []byte("HEIMDALL-OTA-RELEASE-V1\x00")
	manifest = binary.BigEndian.AppendUint16(manifest, uint16(len([]byte(version))))
	manifest = append(manifest, []byte(version)...)
	return append(manifest, digest...), nil
}

func (v *releaseVerifier) verify(deployment Deployment) error {
	if v == nil || len(v.publicKey) != ed25519.PublicKeySize {
		return errors.New("artifact signing key not configured")
	}
	if deployment.Signature == "" || deployment.SignatureAlgorithm == "" || deployment.SigningKeyID == "" {
		return errors.New("artifact signature metadata missing")
	}
	if deployment.SignatureAlgorithm != "Ed25519" {
		return errors.New("artifact signature algorithm unsupported")
	}
	if deployment.SigningKeyID != v.keyID {
		return errors.New("artifact signing key not trusted")
	}
	signature, err := base64.RawURLEncoding.Strict().DecodeString(deployment.Signature)
	if err != nil || len(signature) != ed25519.SignatureSize || base64.RawURLEncoding.EncodeToString(signature) != deployment.Signature {
		return errors.New("artifact signature encoding invalid")
	}
	manifest, err := releaseManifest(deployment.TargetSoftwareVersion, deployment.Checksum)
	if err != nil {
		return err
	}
	if !ed25519.Verify(v.publicKey, manifest, signature) {
		return errors.New("artifact signature verification failed")
	}
	return nil
}

// Match Java String.length/isBlank, including non-breaking spaces and separator controls.
func utf16Units(value string) int {
	units := 0
	for _, r := range value {
		units++
		if r > 0xffff {
			units++
		}
	}
	return units
}
func javaBlank(value string) bool {
	for _, r := range value {
		whitespace := (unicode.IsSpace(r) && r != '\u0085' && r != '\u00a0' && r != '\u2007' && r != '\u202f') || (r >= 0x1c && r <= 0x1f)
		if !whitespace {
			return false
		}
	}
	return true
}
