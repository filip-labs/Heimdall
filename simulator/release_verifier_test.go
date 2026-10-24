package main

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

// PUBLIC TEST KEY from an all-zero seed; never deployment trust material.
func publicTestPrivateKey() ed25519.PrivateKey { return ed25519.NewKeyFromSeed(make([]byte, 32)) }
func testReleaseVerifier() *releaseVerifier {
	return &releaseVerifier{publicKey: publicTestPrivateKey().Public().(ed25519.PublicKey), keyID: "public-test-only-zero-seed"}
}
func signedTestDeployment(t *testing.T, d Deployment) Deployment {
	t.Helper()
	manifest, err := releaseManifest(d.TargetSoftwareVersion, d.Checksum)
	if err != nil {
		t.Fatal(err)
	}
	d.SignatureAlgorithm, d.SigningKeyID = "Ed25519", "public-test-only-zero-seed"
	d.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(publicTestPrivateKey(), manifest))
	return d
}

func TestReleaseManifestAndSignatureMatchSharedJavaVector(t *testing.T) {
	data, err := os.ReadFile("../test-vectors/ota-release-v1.json")
	if err != nil {
		t.Fatal(err)
	}
	var vector map[string]string
	if err := json.Unmarshal(data, &vector); err != nil {
		t.Fatal(err)
	}
	manifest, err := releaseManifest(vector["version"], vector["checksum"])
	if err != nil {
		t.Fatal(err)
	}
	if hex.EncodeToString(manifest) != vector["manifestHex"] {
		t.Fatal("cross-language canonical bytes differ")
	}
	// NBSP is not Java isBlank whitespace; preserve its bytes rather than trimming.
	if _, err := releaseManifest("\u00a0", vector["checksum"]); err != nil {
		t.Fatal(err)
	}
	upper, err := releaseManifest(vector["version"], strings.ToUpper(vector["checksum"]))
	if err != nil || !bytes.Equal(upper, manifest) {
		t.Fatal("checksum normalization differs")
	}
	for _, d := range []Deployment{{TargetSoftwareVersion: vector["version"] + "x", Checksum: vector["checksum"]}, {TargetSoftwareVersion: vector["version"], Checksum: strings.Repeat("cd", 32)}} {
		other, err := releaseManifest(d.TargetSoftwareVersion, d.Checksum)
		if err != nil || bytes.Equal(other, manifest) {
			t.Fatal("manifest did not bind identity")
		}
	}
	verifier, err := newReleaseVerifier(vector["publicKey"], "vector-key")
	if err != nil {
		t.Fatal(err)
	}
	if err := verifier.verify(Deployment{TargetSoftwareVersion: vector["version"], Checksum: vector["checksum"], SignatureAlgorithm: "Ed25519", SigningKeyID: "vector-key", Signature: vector["signature"]}); err != nil {
		t.Fatal(err)
	}
	signed := signedTestDeployment(t, Deployment{TargetSoftwareVersion: vector["version"], Checksum: vector["checksum"]})
	if signed.Signature != vector["signature"] {
		t.Fatal("deterministic signature mismatch")
	}
}

func TestReleasePublicKeyConfigurationFailsClosed(t *testing.T) {
	public, _ := x509.MarshalPKIXPublicKey(publicTestPrivateKey().Public())
	ec, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	nonEd, _ := x509.MarshalPKIXPublicKey(&ec.PublicKey)
	for _, test := range []struct {
		name, key, id string
		valid         bool
	}{
		{"valid", base64.StdEncoding.EncodeToString(public), "key", true},
		{"missing", "", "key", false}, {"bad base64", "not-base64!", "key", false},
		{"bad der", base64.StdEncoding.EncodeToString([]byte("bad")), "key", false},
		{"wrong algorithm", base64.StdEncoding.EncodeToString(nonEd), "key", false},
		{"missing id", base64.StdEncoding.EncodeToString(public), "", false},
	} {
		t.Run(test.name, func(t *testing.T) {
			_, err := newReleaseVerifier(test.key, test.id)
			if (err == nil) != test.valid {
				t.Fatalf("unexpected config result: %v", err)
			}
		})
	}
	t.Setenv("HEIMDALL_OTA_SIGNING_PUBLIC_KEY", "")
	t.Setenv("HEIMDALL_OTA_SIGNING_KEY_ID", "")
	if _, err := loadFleetConfigFromEnv(); err == nil {
		t.Fatal("startup allowed missing trust key")
	}
	t.Setenv("HEIMDALL_OTA_SIGNING_PUBLIC_KEY", base64.StdEncoding.EncodeToString(public))
	t.Setenv("HEIMDALL_OTA_SIGNING_KEY_ID", "configured-key")
	config, err := loadFleetConfigFromEnv()
	if err != nil || config.ReleaseVerifier == nil {
		t.Fatalf("configured trust not loaded: %v", err)
	}
}

func TestReleaseVerificationRejectsUntrustedOrModifiedMetadata(t *testing.T) {
	original := signedTestDeployment(t, Deployment{TargetSoftwareVersion: "2.0.0", Checksum: strings.Repeat("ab", 32)})
	for _, test := range []struct {
		name   string
		change func(*Deployment)
	}{
		{"version", func(d *Deployment) { d.TargetSoftwareVersion = "2.0.1" }},
		{"digest", func(d *Deployment) { d.Checksum = strings.Repeat("cd", 32) }},
		{"bad digest", func(d *Deployment) { d.Checksum = "abc" }},
		{"invalid signature", func(d *Deployment) { d.Signature = base64.RawURLEncoding.EncodeToString(make([]byte, 64)) }},
		{"malformed signature", func(d *Deployment) { d.Signature = "not!base64" }},
		{"short signature", func(d *Deployment) { d.Signature = "YWJj" }},
		{"padded signature", func(d *Deployment) { d.Signature += "==" }},
		{"wrong id", func(d *Deployment) { d.SigningKeyID = "other-key" }},
		{"algorithm", func(d *Deployment) { d.SignatureAlgorithm = "Ed25519ph" }},
		{"unsigned", func(d *Deployment) { d.Signature = "" }},
		{"missing id", func(d *Deployment) { d.SigningKeyID = "" }},
		{"missing algorithm", func(d *Deployment) { d.SignatureAlgorithm = "" }},
	} {
		t.Run(test.name, func(t *testing.T) {
			d := original
			test.change(&d)
			if err := testReleaseVerifier().verify(d); err == nil {
				t.Fatal("untrusted release accepted")
			}
		})
	}
	wrong, _, _ := ed25519.GenerateKey(rand.Reader)
	if err := (&releaseVerifier{publicKey: wrong, keyID: original.SigningKeyID}).verify(original); err == nil {
		t.Fatal("wrong public key accepted")
	}
	for _, version := range []string{"", "\u001c", strings.Repeat("x", 51), string([]byte{0xff}), strings.Repeat("🚗", 26)} {
		if _, err := releaseManifest(version, original.Checksum); err == nil {
			t.Fatal("invalid version accepted")
		}
	}
	for _, digest := range []string{"", strings.Repeat("a", 63), strings.Repeat("a", 65), strings.Repeat("g", 64)} {
		if _, err := releaseManifest("2.0.0", digest); err == nil {
			t.Fatal("invalid digest accepted")
		}
	}
}

func TestArtifactAuthenticityFailureCannotDownloadOrInstallIncludingRollback(t *testing.T) {
	for _, name := range []string{"unsigned", "signature", "wrong public key", "wrong key id", "modified version", "modified bytes", "modified digest and bytes", "unsigned rollback"} {
		t.Run(name, func(t *testing.T) {
			original := []byte("authentic artifact")
			payload := original
			d := signedTestDeployment(t, Deployment{ID: "deployment-1", SourceSoftwareVersion: "1.0.0", TargetSoftwareVersion: "2.0.0", Checksum: checksumFor(original), Status: "PENDING"})
			verifier := testReleaseVerifier()
			switch name {
			case "unsigned":
				d.Signature = ""
			case "signature":
				d.Signature = base64.RawURLEncoding.EncodeToString(make([]byte, 64))
			case "wrong public key":
				public, _, _ := ed25519.GenerateKey(rand.Reader)
				verifier = &releaseVerifier{publicKey: public, keyID: d.SigningKeyID}
			case "wrong key id":
				d.SigningKeyID = "untrusted-key"
			case "modified version":
				d.TargetSoftwareVersion = "3.0.0"
			case "modified bytes":
				payload = []byte("tampered bytes")
			case "modified digest and bytes":
				payload = []byte("tampered bytes")
				d.Checksum = checksumFor(payload)
			case "unsigned rollback":
				d.TargetSoftwareVersion = "0.9.0"
				d.Signature = ""
			}
			var updates []deploymentStatusUpdate
			var mu sync.Mutex
			server := newOTATestServer(t, payload, &updates, &mu)
			defer server.Close()
			d.ArtifactURL = server.URL + "/artifact.bin"
			agent := newOTATestAgent(server, "test-vin", "1.0.0", false)
			agent.ReleaseVerifier = verifier
			// Installation must not even start after either security check fails.
			agent.InstallWait = func(context.Context, time.Duration) error {
				t.Error("installation began after security failure")
				return nil
			}
			err := agent.processDeployment(context.Background(), d)
			if err == nil {
				t.Fatal("untrusted artifact installed")
			}
			assertDeploymentStatuses(t, updates, []string{"DOWNLOADING", "FAILED"})
			if agent.SoftwareVersion != "1.0.0" {
				t.Fatal("failed authenticity changed software version")
			}
			reason := updates[len(updates)-1].FailureReason
			if strings.Contains(reason, d.Signature) && d.Signature != "" {
				t.Fatal("signature leaked in failure reason")
			}
		})
	}
}

func TestSignedRollbackUsesTheSameIntegrityAndAuthenticityPath(t *testing.T) {
	payload := []byte("previous signed release")
	var updates []deploymentStatusUpdate
	var mu sync.Mutex
	server := newOTATestServer(t, payload, &updates, &mu)
	defer server.Close()
	agent := newOTATestAgent(server, "test-vin", "2.0.0", false)
	d := signedTestDeployment(t, Deployment{ID: "deployment-1", SourceSoftwareVersion: "2.0.0", TargetSoftwareVersion: "1.0.0", Checksum: checksumFor(payload), ArtifactURL: server.URL + "/artifact.bin", Status: "PENDING"})
	if err := agent.processDeployment(context.Background(), d); err != nil {
		t.Fatal(err)
	}
	assertDeploymentStatuses(t, updates, []string{"DOWNLOADING", "DOWNLOADED", "INSTALLING", "INSTALLED"})
	if agent.SoftwareVersion != "1.0.0" {
		t.Fatal("signed rollback did not install")
	}
}

func TestResumedDeploymentsStillRequireAuthenticityAndCleanTemporaryArtifact(t *testing.T) {
	t.Setenv("TMPDIR", t.TempDir())
	payload := []byte("valid digest but unsigned resumed artifact")
	for _, state := range []string{"DOWNLOADING", "DOWNLOADED", "INSTALLING"} {
		t.Run(state, func(t *testing.T) {
			var updates []deploymentStatusUpdate
			var mu sync.Mutex
			server := newOTATestServer(t, payload, &updates, &mu)
			defer server.Close()
			agent := newOTATestAgent(server, "test", "1.0.0", false)
			d := Deployment{ID: "deployment-1", SourceSoftwareVersion: "1.0.0", TargetSoftwareVersion: "2.0.0", Checksum: checksumFor(payload), ArtifactURL: server.URL + "/artifact.bin", Status: state}
			if err := agent.processDeployment(context.Background(), d); err == nil {
				t.Fatal("resume bypassed signature verification")
			}
			assertDeploymentStatuses(t, updates, []string{"FAILED"})
			if agent.SoftwareVersion != "1.0.0" {
				t.Fatal("unsigned resume installed")
			}
			files, err := os.ReadDir(os.TempDir())
			if err != nil {
				t.Fatal(err)
			}
			if len(files) != 0 {
				t.Fatal("failed verification left a temporary artifact")
			}
		})
	}
}
