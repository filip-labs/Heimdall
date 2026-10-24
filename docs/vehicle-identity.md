# M11A vehicle identity and enforcement

Vehicle creation (`POST /api/v1/vehicles`) remains **trusted provisioning**.
Restrict access to this operation to trusted infrastructure. Use HTTPS for
all vehicle API traffic outside disposable local environments. This implementation
adds device identity, not user IAM, operator accounts, RBAC or fleet PKI.
mTLS is intentionally deferred to the M11C evaluation/ADR.

## Credential lifecycle

Each newly created vehicle receives 32 bytes from Java `SecureRandom` (256 bits),
encoded as 43 Base64 URL-safe characters without padding. The backend persists
only the SHA-256 digest as lowercase hex in `vehicle.credential_hash`. The vehicle
UUID primary key binds one verifier to one vehicle; no VIN-derived secret is used.
Verification hashes the submitted credential and compares digest bytes with
`MessageDigest.isEqual`, including a dummy comparison for absent verifiers.

The creation response keeps all existing vehicle fields at the top level and adds
`credential`. Save it securely on the device at provisioning: it is revealed once,
cannot be retrieved again, and is absent from GET, list, VIN lookup and heartbeat
responses. Duplicate VIN creation still returns 409 and does not reset the secret.
The provisioning response uses `Cache-Control: no-store`. Do not log request or
response bodies containing credentials or tokens, including in gateways/APM.

Migration `V11__add_vehicle_credential.sql` adds a nullable 64-character verifier
with a hex-format check. Existing rows stay null and cannot authenticate. For
disposable local environments, reset/recreate vehicles through provisioning; no
credential reset/rotation/revocation API is included in this slice. SQL never
creates secrets that an operator cannot retrieve.

## Token API

```http
POST /api/v1/vehicle-auth/token
Content-Type: application/json

{"vehicleId":"<vehicle UUID>","credential":"<one-time provisioning credential>"}
```

Success returns 200 with `Cache-Control: no-store`:

```json
{"accessToken":"<signed JWT>","tokenType":"Bearer","expiresIn":300}
```

Unknown UUID, incorrect credential, cross-vehicle credentials and legacy vehicles
all return 401 with the same problem detail, `Vehicle authentication failed`.
Malformed requests return 400. Database lookup timing is not guaranteed identical;
this API does not implement rate limiting. Protect the trusted provisioning and
token interfaces at deployment boundaries as appropriate.

JWTs use Auth0 `java-jwt` with HS256. Claims are exactly `sub` (canonical vehicle
UUID), `iss` (`heimdall`), `iat` and `exp`. Default lifetime is **5 minutes**.
The validator pins HS256, checks the signature and issuer, requires all four
claims, rejects future issuance and expiration (including equality at the expiry
boundary), and enforces a canonical UUID subject and a positive lifetime no longer
than the configured TTL. Time comes from injected `Clock`; tests need no sleeps.
The token validator returns only the verified vehicle UUID. Protected operations
compare that identity to the vehicle owning the requested resource.

## Configuration

Set a Base64-encoded random signing key with at least 32 decoded bytes:

```bash
export HEIMDALL_SECURITY_VEHICLE_TOKEN_SECRET="$(openssl rand -base64 32)"
export HEIMDALL_SECURITY_VEHICLE_TOKEN_TTL=5m
./mvnw spring-boot:run
```

The corresponding properties are `heimdall.security.vehicle-token.secret` and
`heimdall.security.vehicle-token.ttl`. TTL accepts whole seconds from 1s to 15m.
There is no application default signing secret: startup fails if the key is
missing, malformed or too short. All replicas must share a securely supplied key;
changing it invalidates existing tokens. The example creates a fresh local key
each time it runs. Persist production keys in deployment secret management.
Hibernate explicitly selects its Jackson 3 JSON mapper to preserve existing
outbox serialization when java-jwt adds Jackson 2 to the classpath.
A deterministic test-only key lives under `src/test/resources` and is not packaged
with the application. No CI configuration change is required.

HS256 is a shared-key tradeoff: every verifier holding the key can issue tokens.
Credential hashing is appropriate here because inputs are high-entropy generated
secrets, not human passwords. Tokens carry no credential, digest, VIN or roles.

## Protected operations and ownership

These endpoints require `Authorization: Bearer <accessToken>`:

- `POST /api/v1/vehicles/{vehicleId}/heartbeat`
- `GET /api/v1/vehicles/{vehicleId}/deployments/active`
- `PATCH /api/v1/deployments/{deploymentId}/status`

A small MVC interceptor authenticates only methods annotated `VehicleAuthenticated`
before request-body binding or controller execution. It accepts exactly one
Authorization header, case-insensitive `Bearer`, one space, and one compact JWS.
Duplicate headers, extra fields, wrong schemes, malformed/expired tokens and bad
signatures return generic **401**, without token parser details. The HTTP server
may normalize HTTP field whitespace before application parsing.

The token subject is the identity; URL, VIN and request bodies are never proof of
identity. Heartbeat and polling require the subject to equal the path vehicle UUID.
A different authenticated vehicle receives **403**, whether or not the target has
an active deployment. Deployment status uses the actual deployment owner's UUID:
the check runs on the same pessimistically locked entity inside the mutation
transaction, before even the idempotent-status branch. Rejected calls do not
change vehicle state, deployment history/software version or rollout state.
A genuinely missing resource returns **404** after identity checks; invalid state
transitions still return **409**.

Trusted provisioning, release/rollout creation, rollback controls and administrative
reads remain control-plane operations. They do not use vehicle tokens. Deployment
status is a vehicle callback, including status reports for rollback deployments.
Operator IAM and protection of administrative reads remain separate concerns.

## Simulator lifecycle

Each Go agent retains its provisioning credential and access token **only in memory**.
Bootstrap captures the one-time credential and authenticates before starting
heartbeat/poll loops. A 409 registration response now fails clearly: VIN lookup
cannot recover a credential. Use a fresh disposable simulator/database environment
for repeat runs; back up any data you need before resetting a local environment.
No credential store or reset endpoint is added. Deterministic VINs and OTA failure
injection remain unchanged. Never print an agent's internal state or HTTP bodies
containing secrets; normal simulator errors omit authentication/protected response
bodies and parser details.

Token caching uses the server's `expiresIn`, anchored before the auth request.
Agents refresh on the next protected call using a reserve of 20% of the lifetime
plus one second for JWT timestamp truncation. The reserve is capped at 80% of the
lifetime so even a 1-second TTL has a positive cache interval (200ms), avoiding an
immediate refresh loop. An agent coordinates one in-flight refresh shared by its
heartbeat and deployment loops; waiting callers respect context cancellation.
Expiry metadata is approximate (JWT dates have second precision), so **401** also
invalidates the matching token generation, authenticates again and retries the
original request **once**, replaying status request bodies. A second 401 is surfaced
and invalidates that token for later calls. A delayed response cannot invalidate a
newer token generation. **403 never triggers reauthentication**.

## Historical M10 tooling

The existing k6 heartbeat/poll/mixed workloads resolve UUIDs by VIN and do not keep
provisioning credentials. They are **historical M10 tooling**, incompatible with
these protected endpoints: against M11A they receive 401. VIN lookup cannot repair
that. Run historical measurements at their documented M10 commit with its matching
scripts/configuration. Do not use the current M11A backend as an M10 benchmark target.
Registration-only workloads still exercise the trusted provisioning boundary.

Historical result files and benchmark SHAs are unchanged. Auth-aware k6 workload
support and new performance measurements are deferred; do not compare authenticated
M11 traffic directly to the published M10 numbers. Unit tests of M10 tooling remain
valid and are run as regression checks, not as authenticated performance evidence.

## A2 verification evidence

Local validation on 2026-10-24 passed 102 Java tests (including 8 new parsing and
endpoint enforcement tests), simulator tests and the Go race detector, 33 M10B
checks, 48 M10C checks and 8 load-harness unit tests. A1 token/credential tests and
existing OTA, rollout and Kafka tests remain enabled.

Two live scenarios used the actual Java 21 application and Go simulator against
separate disposable PostgreSQL 17 databases, with Kafka disabled and rollout
evaluation set to 300ms. Each provisioned five deterministic vehicles and ran a
40% → 100% rollout from 1.0.0 to 2.0.0 using an HTTP-served checksummed artifact.
An observing local proxy counted token exchanges and protected response statuses;
credentials and tokens were held in memory and excluded from reported evidence.

| Token TTL | Authenticated vehicles | Minimum token exchanges per vehicle | Outcome |
|---|---:|---:|---|
| Default 5m | 5 | 1 | 5 INSTALLED; rollout COMPLETED; 0 HTTP 401/403 |
| Configured 2s | 5 | 8 | 5 INSTALLED; rollout COMPLETED; 0 HTTP 401/403 |

Both runs verified heartbeat timestamps, final vehicle versions and the exact
PENDING → DOWNLOADING → DOWNLOADED → INSTALLING → INSTALLED history for every
deployment. Process logs contained none of the generated provisioning credentials,
access tokens or signing keys. Temporary processes and databases were removed.
These are functional checks, not M10 performance measurements.

## Scope and limitations

M11A (#42) implements A1 plus A2. Credentials cannot be recovered;
rotation/revocation and rate limiting remain out of scope. A valid token remains
usable until expiry or signing-key change.
HS256 requires shared signing authority and secure key distribution. HTTPS is
required outside isolated local testing; no production OEM PKI is claimed.
Release/artifact signing is implemented in **M11B**; see [OTA security](ota-security.md).
M11C records the [threat model](threat-model.md), [security regressions](security-regression.md)
and [ADR-001](adr/001-mtls-device-authentication.md): mTLS is intentionally deferred
in the current portfolio scope.
