# M11A vehicle identity foundation

Vehicle creation (`POST /api/v1/vehicles`) remains **trusted provisioning**.
Restrict access to this operation to trusted infrastructure. Use HTTPS for
provisioning and authentication outside disposable local environments. This slice
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
A token validator returns only the verified vehicle UUID. The next slice must
compare it to the vehicle owning the requested resource, including deployments.

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

## Scope and next phase

This is the identity/token foundation for #42. Heartbeat, active-deployment polling
and deployment status updates **do not yet enforce tokens**. The simulator remains
unchanged and can deserialize the compatible flat provisioning response. The next
M11A slice must integrate token acquisition/storage in the simulator and enforce
verified vehicle identity on vehicle-facing operations. Release signing belongs to
M11B. This foundation does not complete #42.
