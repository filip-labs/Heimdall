# OTA artifact integrity and authenticity (M11B)

Vehicles require **both** checks before installation:

- SHA-256 integrity: downloaded bytes match the expected artifact digest.
- Ed25519 authenticity: the configured release authority authorized the manifest
  binding that digest to the target software version.

A matching hash alone does not identify a trusted publisher. The vehicle trusts
only its locally configured Ed25519 public key and expected key ID; keys supplied
in deployment metadata are never accepted. Java signs using the platform
`java.security.Signature`/`KeyFactory`; Go verifies using `crypto/ed25519` and
`crypto/x509`. No external cryptographic dependency is added.

## Canonical manifest V1

Sign these bytes exactly, without JSON, Java `toString()`, a BOM or a newline:

```text
ASCII("HEIMDALL-OTA-RELEASE-V1") || 0x00
|| uint16_big_endian(length(version_utf8))
|| UTF8(version)
|| SHA256_digest_raw_bytes[32]
```

Version text is not trimmed or normalized. It must be valid Unicode and fit the
existing 50 UTF-16-code-unit limit; the encoded **byte** length, not character
count, is written into the manifest. Checksums must be exactly 64 hexadecimal
characters, without whitespace or prefixes. Normalize hex to lowercase before
persistence and decode it to 32 bytes for signing. Algorithm is plain **Ed25519**,
not Ed25519ph or Ed25519ctx.

Only version and digest are signed. The artifact URL is a mirror/delivery location;
description is non-security metadata. Neither is part of artifact identity.
A valid signature is public metadata; it is encoded as 86 Base64 URL-safe
characters without padding (64 decoded bytes).

The shared public test vector is `test-vectors/ota-release-v1.json`. Java and Go
tests assert the same canonical bytes and deterministic signature, including a
version with multibyte UTF-8 characters. The vector and the test-only application
key deliberately use an **all-zero seed**. They are public test fixtures, never
deployment trust material. Private test encoding appears only under
`src/test/resources`; production has no signing-key default and does not package
those resources.

## Backend and APIs

`POST /api/v1/releases` validates and normalizes SHA-256, builds the canonical
manifest, signs it and only then persists the release. A signing failure cannot
save an unsigned new release. Duplicate-version behavior is unchanged.

Flyway `V12__add_release_signature.sql` adds:

- `signature_algorithm` (`Ed25519`)
- `signing_key_id` (configured public identifier)
- `signature` (unpadded Base64URL signature)

The database permits either all-null legacy metadata or complete Ed25519 metadata
with nonempty key ID and correctly shaped signature. Existing migrations remain
unchanged; V12 never invents signatures or rewrites old checksums.

`SoftwareReleaseResponse` and `DeploymentResponse` expose `signatureAlgorithm`,
`signingKeyId` and `signature`. Deployment values come directly from the linked
release. Private keys are never included in entities, responses or deployments.
Backend signing configuration is separate from the existing HS256 vehicle-token
key; M11A authentication/ownership enforcement remains enabled.

## Vehicle verification and failure

For PENDING deployments, the simulator follows:

```text
DOWNLOADING → download → SHA-256 → Ed25519 → DOWNLOADED → INSTALLING → INSTALLED
```

The verifier rejects missing/unsigned metadata, unsupported algorithms, wrong key
IDs, malformed/noncanonical signature encodings, wrong public keys, invalid
signatures, and changed target version or digest. A caller who changes both the
artifact bytes and expected digest still cannot forge the manifest signature.
Checksum mismatches keep their existing non-transient, no-retry behavior.

Security failure uses the existing FAILED callback/event path. It cannot advance
to DOWNLOADED/INSTALLING/INSTALLED or change the vehicle software version. The
failure reason describes the category without key bytes, signature contents or
cryptographic stack traces. Temporary artifacts are removed after verification
failure or completion. Resumed DOWNLOADING, DOWNLOADED and INSTALLING deployments
repeat both checks before further progress; terminal deployments remain terminal.

Rollback is a normal deployment of its linked release. Signed rollback artifacts
use exactly the same verification path; there is no authenticity bypass. Legacy
unsigned releases (including rollback targets) remain readable in the backend but
are **rejected by the M11B vehicle**. Create a new signed release through trusted
provisioning/release operations rather than automatically trusting historical rows.

## Local key generation and configuration

Generate fresh local keys outside the repository with OpenSSL. These commands
create unencrypted private-key files with restrictive permissions; keep them in a
secure location and remove them when the disposable environment is no longer needed.

```bash
umask 077
OTA_KEY_DIR=$(mktemp -d)
openssl genpkey -algorithm Ed25519 -out "$OTA_KEY_DIR/private.pem"
openssl pkcs8 -topk8 -nocrypt -in "$OTA_KEY_DIR/private.pem" \
  -outform DER -out "$OTA_KEY_DIR/private.der"
openssl pkey -in "$OTA_KEY_DIR/private.pem" -pubout \
  -outform DER -out "$OTA_KEY_DIR/public.der"
```

Backend configuration (private **PKCS#8 DER**, standard Base64):

```bash
export HEIMDALL_SECURITY_RELEASE_SIGNING_PRIVATE_KEY="$(openssl base64 -A -in "$OTA_KEY_DIR/private.der")"
export HEIMDALL_SECURITY_RELEASE_SIGNING_KEY_ID=local-ed25519-v1
# Existing M11A key is also required:
export HEIMDALL_SECURITY_VEHICLE_TOKEN_SECRET="$(openssl rand -base64 32)"
./mvnw spring-boot:run
```

Properties are `heimdall.security.release-signing.private-key` and
`heimdall.security.release-signing.key-id`. Missing/invalid keys or key IDs fail
startup; there is no production or local default signing key. Error messages and
configuration string representations redact private material. Default actuator
exposure remains health/info/prometheus; do not expose configuration or environment
values in deployment diagnostics.

In the simulator environment, provision **only the public key** (X.509
SubjectPublicKeyInfo DER, standard Base64) and matching key ID:

```bash
export HEIMDALL_OTA_SIGNING_PUBLIC_KEY="$(openssl base64 -A -in "$OTA_KEY_DIR/public.der")"
export HEIMDALL_OTA_SIGNING_KEY_ID=local-ed25519-v1
(cd simulator && go run .)
```

For separate processes/machines, transfer only the public key through a trusted
configuration channel. Never copy backend private-key variables to a vehicle.
Simulator startup rejects absent/malformed/non-Ed25519 public keys and missing key
IDs. It never needs the signing private key. Do not print keys or commit generated
PEM/DER files. Use a real artifact SHA-256 (for example `openssl dgst -sha256 fw.bin`)
when creating a release; old placeholder strings such as `abc` now return 400.

## Live functional verification

On 2026-10-24, isolated Java 21 / Go simulator runs used fresh PostgreSQL 17
state, randomly generated Ed25519 signing keys, M11A vehicle authentication,
2-second vehicle-token TTL and checksummed artifact bytes served over local HTTP.
Each scenario provisioned five deterministic vehicles. Kafka was disabled and
rollout evaluation was configured to 300ms.

| Scenario | Rollout stages | Deployment outcome | Vehicle version | Rollout outcome |
|---|---|---|---|---|
| Matching signing/verification key | 40%, 100% | 5 INSTALLED | 2.0.0 | COMPLETED |
| Independent wrong public key, same key ID | 100% | 5 FAILED | 1.0.0 | PAUSED |
| Changed artifact bytes with original manifest | 100% | 5 FAILED | 1.0.0 | PAUSED |

Positive histories were PENDING → DOWNLOADING → DOWNLOADED → INSTALLING → INSTALLED.
Negative histories were exactly PENDING → DOWNLOADING → FAILED. Wrong-key failures
reported signature verification failure; changed-byte failures reported checksum
mismatch. All five vehicles authenticated in each run, with zero HTTP 401/403.
No generated private key, vehicle credential or token appeared in process logs.
Temporary processes and databases were removed. These are functional checks, not
M10 performance measurements. Automated tests additionally cover signed and
unsigned rollback, resumed deployments, malformed metadata and cleanup.

## Boundaries and limitations

Trusted release creation grants signing authority: protect that control-plane API
and its private-key environment. Use HTTPS outside isolated local testing. Keys
are loaded from configuration into process memory; production should use secure
key management/HSM/KMS, but that integration is outside this project milestone.

M11B does not provide HSM, cloud KMS, manufacturing PKI, certificate lifecycle,
automatic signing-key rotation, key distribution/revocation, or hardware-backed
vehicle trust storage. One pinned key/key ID is supported per simulator process.
Signatures do not impose monotonic/anti-rollback policy or bind to a vehicle; an
explicitly deployed older signed version can be installed. This supports the
existing rollback model. Authentication and artifact authenticity are separate.

M11C documents the [threat model](threat-model.md), [security regression matrix](security-regression.md)
and [ADR-001](adr/001-mtls-device-authentication.md), which intentionally defers mTLS
in the current portfolio scope. No production OEM PKI is claimed.
Historical M10 evidence remains unchanged and does not measure this signing flow;
M11 signing performance must not be presented as equivalent to M10 results.
