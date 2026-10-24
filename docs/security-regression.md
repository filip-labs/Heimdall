# Security regression matrix

M11C validates the M11A identity boundary and M11B OTA trust boundary together.
Java exercises real HTTP APIs with PostgreSQL; Go exercises the actual vehicle
verification/install path against local HTTP test servers. No Java reimplementation
of the vehicle verifier is used. Existing Go security tests are retained unchanged.

## Backend identity and state

Tests below are in
[`SecurityRegressionIntegrationTest`](../src/test/java/dev/heimdall/security/SecurityRegressionIntegrationTest.java).
Fixtures provision vehicles, exchange their credentials for tokens, create signed
releases and create deployments through HTTP. Expiration uses an injected clock.

| Security case | Expected result | Automated test |
|---|---|---|
| Missing token | 401 on heartbeat, polling and status callback | `missingForgedAndExpiredTokensRejectEveryVehicleOperationWithoutMutation` |
| Forged signature | 401 on all three operations | Same test |
| Expired token, including exact expiry | 401 on all three operations | Same test |
| A sends heartbeat as B | 403 | `oneVehicleCannotHeartbeatPollOrReportAnotherVehiclesDeployment` |
| A polls B | 403 | Same test |
| A reports B's deployment status | 403 for both a normally allowed transition and a terminal report | Same test |
| Valid authenticated owner | Heartbeat and own poll succeed; signed metadata delivered; allowed transitions end in INSTALLED | `authenticatedOwnerReceivesSignedMetadataAndCompletesAllowedTransitions` |

Both negative tests compare full vehicle/deployment/event snapshots before and
after requests: `lastSeenAt`, connectivity, software version, deployment status,
timestamps and event history remain unchanged. Positive history is exactly
PENDING → DOWNLOADING → DOWNLOADED → INSTALLING → INSTALLED, with the target version
applied. This callback test establishes API authorization, not proof of installation
on a physical device.

[`VehicleEndpointSecurityTest.knownVehicleOperationsRequireAuthentication`](../src/test/java/dev/heimdall/security/VehicleEndpointSecurityTest.java)
protects the three known controller methods against removal of `@VehicleAuthenticated`.
New vehicle-facing endpoints still require explicit review and coverage; this test
does not infer future endpoints or provide a default-deny routing policy.

Lower-level coverage remains in
[`VehicleAuthenticationIntegrationTest`](../src/test/java/dev/heimdall/vehicleauth/VehicleAuthenticationIntegrationTest.java)
(one-time secret, verifier-only storage, wrong/crossed credentials, unknown and legacy
vehicles), [`VehicleTokenServiceTest`](../src/test/java/dev/heimdall/vehicleauth/VehicleTokenServiceTest.java)
(algorithm/signature, issuer, subject, required claims and lifetime), and
[`VehicleEnforcementIntegrationTest`](../src/test/java/dev/heimdall/vehicleauth/VehicleEnforcementIntegrationTest.java)
(header parsing, ownership before idempotence/state-machine mutation, 404 behavior).

## Artifact trust at the vehicle boundary

`Verifier` and `OTA failures` below refer respectively to
`TestReleaseVerificationRejectsUntrustedOrModifiedMetadata` and
`TestArtifactAuthenticityFailureCannotDownloadOrInstallIncludingRollback` in
[`release_verifier_test.go`](../simulator/release_verifier_test.go).

| Security case | Expected result | Automated test / table case |
|---|---|---|
| Tampered artifact bytes | Checksum failure; FAILED; no installation | OTA failures / `modified bytes`; `TestDownloadAndVerifyArtifactDoesNotRetryChecksumMismatch` in [main_test.go](../simulator/main_test.go) |
| Malformed SHA-256 metadata | API 400 without persistence; verifier rejects | `ReleaseSigningIntegrationTest.malformedChecksumsReturn400WithoutPersisting` in [Java integration tests](../src/test/java/dev/heimdall/releasesigning/ReleaseSigningIntegrationTest.java); Verifier / `bad digest` |
| Unsigned legacy release | FAILED; no installation | OTA failures / `unsigned`, `unsigned rollback` |
| Invalid Ed25519 signature with valid digest | FAILED; no installation | OTA failures / `signature` |
| Wrong public key (even same key ID) | FAILED; no installation | OTA failures / `wrong public key` |
| Wrong signing key ID | FAILED; no installation | OTA failures / `wrong key id` |
| Modified signed version | Signature failure; FAILED | OTA failures / `modified version` |
| Modified signed digest | Signature rejected, including changed matching bytes | Verifier / `digest`; OTA failures / `modified digest and bytes` |
| Valid SHA-256 and signature | Installation permitted; software version changes | `TestProcessDeploymentInstallsNormally` in [main_test.go](../simulator/main_test.go) |
| Valid signed older rollback | Same integrity/authenticity checks, installation permitted | `TestSignedRollbackUsesTheSameIntegrityAndAuthenticityPath` |
| Resumed deployment with untrusted manifest | FAILED; no installation; temporary file removed | `TestResumedDeploymentsStillRequireAuthenticityAndCleanTemporaryArtifact` |

Every OTA failure table case asserts that installation is never invoked, software
stays at the previous version, and callbacks are exactly DOWNLOADING → FAILED;
DOWNLOADED, INSTALLING and INSTALLED are absent. PENDING is the backend's preexisting
creation event, not an extra callback in this unit harness. The M11B live probes in
[OTA security](ota-security.md#live-functional-verification) additionally observed
PENDING → DOWNLOADING → FAILED persisted end to end for five wrong-key and five
changed-byte deployments. This is historical functional evidence, not an M11C rerun.

The shared Java/Go manifest vector and explicit U+0085 tests in
[`ReleaseSigningTest`](../src/test/java/dev/heimdall/releasesigning/ReleaseSigningTest.java)
and `release_verifier_test.go` protect canonical-byte compatibility. Tests use
public test-only fixtures, never deployment credentials or production keys.

## Running validation

```bash
./mvnw test
(cd simulator && go test ./...)
(cd simulator && go test -race ./...)
python3 -m unittest discover -s scripts/m10b/tests -v
python3 -m unittest discover -s scripts/m10c/tests -v
npm --prefix load-tests test
git diff --check
```

Java requires Java 21 and Docker for PostgreSQL Testcontainers. M10 tooling tests
are regression checks; historical unauthenticated workload scripts do not establish
performance or security behavior of the M11 API. See the
[threat model](threat-model.md) for guarantees these tests deliberately do not claim.

Local validation on 2026-10-24 passed 116 Java tests (four new M11C tests), Go tests
and the race detector, 33 M10B tests, 48 M10C tests and eight load-harness tests.
M10C's real JVM attach checks require execution outside the restricted sandbox;
the initial sandbox run failed those three checks, and the permitted rerun passed
all 48. No large performance experiment or additional live fleet probe was run.
