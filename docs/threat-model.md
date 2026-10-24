# Heimdall Threat Model

## Scope

This model covers the current Spring Boot control plane, PostgreSQL state and
outbox, internal Kafka processing, Go vehicle simulator and HTTP artifact delivery.
M11A provides vehicle identity; M11B provides signed OTA manifests; M11C documents
and regression-tests their boundaries. It does not establish production automotive
security or safety certification. [Regression traceability](security-regression.md)
links claims to executable tests.

## Assets

| Asset | Location and sensitivity |
|---|---|
| Per-vehicle provisioning credential | Random 32-byte secret, disclosed once; simulator memory only |
| Credential verifier | SHA-256 verifier in vehicle row; no plaintext credential stored |
| Vehicle JWT and UUID binding | Short-lived bearer token in simulator memory; signed subject is immutable vehicle UUID, not VIN |
| HS256 token signing key | External backend configuration, shared across replicas; secret signing authority for all vehicle identities |
| Artifact, SHA-256 digest and release version | Downloaded bytes plus public release metadata; digest alone provides no publisher authentication |
| Ed25519 signature and signing key ID | Public release/deployment metadata; signature binds canonical version and digest; key ID is a selector, not proof of trust |
| Release signing private key | External Base64 PKCS#8 Ed25519 backend configuration; never sent to vehicles or stored in release rows |
| Verification public key | Independently pinned Base64 SPKI Ed25519 key and expected ID in simulator configuration; public, but integrity-critical |
| Fleet/control state | Vehicle identifiers/version/connectivity, deployments, rollouts, event history and transactional outbox in PostgreSQL |

Credentials have high random entropy; their unsalted verifier is not a human password
hash. This does not protect against an attacker who can overwrite database rows.
Runtime keys must be supplied securely; checked-in test keys are public fixtures
and must never be used for deployment. There is no built-in credential/token-key
rotation or revocation API. One active OTA verification key/ID is supported per
simulator process; key lifecycle and secure hardware storage are not implemented.

## Actors

Trusted actors are the provisioning/release operator and backend, and vehicles
executing the configured verifier honestly. An artifact host distributes bytes but
is not trusted to authorize them. Adversaries include unauthenticated network
clients, rogue vehicles holding one valid identity, credential/token thieves,
artifact-host or byte-tampering attackers, plaintext-network attackers, and
attackers with database, operator, backend or signing-key access. Their compromise
levels are different; possessing one vehicle credential does not grant another
vehicle's protected operations or the Ed25519 private key.

## Trust Boundaries

### Trusted provisioning and control plane

Vehicle provisioning, release creation, deployment/rollout controls and general
administrative reads assume a trusted operator/network. Human IAM/RBAC is absent.
These routes are not secured merely by the vehicle token interceptor. In particular,
access to release creation grants use of backend signing authority. Exposing all
routes publicly defeats this trust assumption even if vehicle tokens are correct.
Production deployment must isolate/authenticate/authorize this control plane.

### Vehicle API

The identity chain is provisioned credential → authenticated short-lived HS256 JWT
→ canonical vehicle UUID subject → resource ownership check. Heartbeat and active
deployment polling check the path UUID. Status callbacks lock the deployment and
check its actual vehicle owner inside the transaction, before idempotence or state
mutation. Missing/invalid/expired tokens return 401; another vehicle's resource
returns 403. VIN knowledge or an arbitrary UUID alone is insufficient.

The interceptor is annotation-driven: only methods explicitly annotated
`@VehicleAuthenticated` are protected. The three current methods have an executable
coverage check. Future vehicle endpoints require explicit annotation and ownership
review; there is no universal default-deny API policy.

### Artifact delivery and vehicle verification

The Go agent hashes downloaded bytes with SHA-256, then verifies the canonical
version/digest manifest using its pinned Ed25519 public key and expected key ID.
Deployment metadata cannot replace that key. Changing bytes and their advertised
digest still requires a new valid signature. DOWNLOADED/installation occurs only
after both checks; failure preserves software version. Resumed and rollback paths
use the same checks; unsigned legacy releases fail closed.

The signature does not bind artifact URL, description, deployment/vehicle UUID or
rollout policy. It authenticates a version/digest pair, not freshness or approval
for a particular vehicle. A valid older signed release can install through rollback.

### Backend signing and internal state

The backend is trusted with the Ed25519 private key; vehicles receive only public
verification material. HS256 and Ed25519 keys have separate roles and configuration.
Backend compromise can use signing authority. PostgreSQL and Kafka are trusted
internal infrastructure; transactions/locks/outbox provide consistency, not
cryptographic protection against a privileged infrastructure attacker. Event
history is append-only through application flows, not tamper-proof against DB write
access. No hardware attestation proves that a vehicle reporting INSTALLED really
executed the artifact.

## Security Properties

An unauthenticated client cannot execute the three protected vehicle operations.
A vehicle token authorizes only its own resource, including status callbacks.
Rejected requests preserve heartbeat/connectivity, deployment/event state and
software version. An honest agent requires both matching bytes and a manifest
signed by its pinned key before installation. These properties rely on uncompromised
keys, code and configuration; they do not imply operator authorization, freshness,
availability or trustworthy reports from a compromised vehicle.

## Threats and Mitigations

| Threat | Current mitigation | Residual risk | Production extension |
|---|---|---|---|
| Arbitrary VIN/UUID impersonation | Random credential, signed UUID subject | Stolen credential impersonates that vehicle | Secure enrollment, device certificate/private key |
| Cross-vehicle access | Ownership checks, locked status transaction, regression tests | New endpoint missing annotation/guard; application defects | Default-deny routing and centralized authorization review |
| Token theft/replay | Default 5-minute TTL, bounded lifetime, per-vehicle subject | Replay within TTL; no server-side revocation | TLS, revocation policy, certificate-bound tokens if used |
| Credential theft | One-time disclosure, verifier-only DB storage, simulator memory | No rotation/revocation; compromised process sees secret | Protected device storage and credential lifecycle |
| HS256 key theft | External configuration; separate from OTA key | Attacker can mint tokens for all vehicle UUIDs | Managed secrets, rotation and issuer isolation |
| Malicious artifact host / changed bytes | SHA-256 followed by Ed25519 verification | Download blocking/resource exhaustion | Distribution availability, egress and resource limits |
| Changed artifact plus changed checksum/version | Signature binds canonical version/digest | Valid signed pair can be replayed | Approval policy and freshness/target binding where required |
| Ed25519 signing-key theft | External private key; vehicle holds public key only | Critical: attacker can forge authentic-looking releases | HSM/KMS, controlled signing pipeline, rotation/revocation and audit |
| Signed release replay/downgrade | Signature verification also on rollback | Authentic does not mean newest; signed older versions allowed | Monotonic counters and explicit emergency rollback policy |
| Trusted operator/API compromise | Explicit trusted-network boundary | Broad fleet control and use of signing API; no human IAM | Operator IAM/RBAC, approval workflow and independent audit |
| Plaintext network attacker | Local-only HTTP assumption; signatures protect artifact contents | Credential/token theft, spoofed callbacks, replay and DoS | TLS on every boundary; evaluate mTLS separately |
| Database compromise | No plaintext vehicle credential or signing key in domain tables | Fleet disclosure; write access can replace verifier, remap ownership, alter state/events or replay signed releases | Least privilege, isolation, monitoring, backup and external audit |
| Request flood / unavailable services | Existing functional retry/reconciliation | No auth rate limiting or DoS immunity; backend/artifact outage | Rate limits, quotas, bounded downloads and availability controls |

## Residual Risks

The major limits are trusted operator APIs, bearer-token replay, credential/key
lifecycle gaps, annotation coverage, signed downgrade acceptance and availability.
A stolen credential remains useful for obtaining fresh tokens; expiry alone does
not revoke that credential. A compromised vehicle can lie about its own deployment
status. A compromised backend can sign releases; a database writer can undermine
identity and audit even without extracting a private key. SHA-256 and Ed25519 do
not repair those trust failures.

## Out-of-Scope Production Controls

There is no manufacturing enrollment/PKI, fleet certificate lifecycle, HSM/KMS
integration, hardware-backed vehicle key storage, secure boot/attestation, anti-rollback
counter, operator IAM, signing approval service, rate limiter or built-in key
rotation/revocation system. These need architectural and operational design, not
claims of completeness based on a local demo. See [ADR-001](adr/001-mtls-device-authentication.md)
for the intentionally deferred mTLS decision and a production evolution path.

## Assumptions

Provisioning discloses credentials only to the intended vehicle over a protected
channel. Backend hosts, replicas, secret delivery, clocks and vehicle public-key
configuration are trusted. Internal database/broker access is restricted. Vehicles
execute the verifier honestly. Production requires TLS to protect credentials,
bearer tokens and control traffic; `http://localhost` is for isolated development.
Application-layer signatures do not make plaintext bearer authentication safe.
