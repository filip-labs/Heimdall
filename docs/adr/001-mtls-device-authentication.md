# ADR-001: Vehicle mTLS and Device Certificates

Status: Accepted

Date: 2026-10-24

## Context

Heimdall currently uses per-vehicle random provisioning secrets, authenticated
short-lived bearer tokens with a vehicle UUID subject, and resource ownership
checks. This demonstrates application identity and authorization in a Go fleet
simulator. It assumes trusted provisioning and protected transport; it does not
model manufacturing enrollment or a physical vehicle's protected private key.

mTLS can authenticate both TLS peers. With device certificates, the server would
validate a client certificate chain and proof of private-key possession, then map
the certified identity to the backend vehicle UUID. TLS authentication does not
replace resource authorization or artifact signing. The protocol basis is
[TLS 1.3 authentication](https://www.rfc-editor.org/rfc/rfc8446.html#section-4.4);
certificate profiles, path validation and revocation are specified in
[RFC 5280](https://www.rfc-editor.org/rfc/rfc5280.html).

Unlike sending a reusable provisioning secret, device authentication proves
possession without transmitting the private key. It can support hardware-backed
keys, mutual authentication and a stronger manufacturing identity link. A managed
certificate lifecycle also provides rotation/revocation mechanisms; these benefits
depend on actual enrollment and enforcement policies.

## Decision

**mTLS is intentionally deferred in Heimdall's current portfolio scope.**

Retain the existing vehicle credential → short-lived token → UUID ownership model.
Require TLS for deployed traffic independently of this mTLS decision. Document the
limits and validate the current mechanisms instead of adding a local mini-PKI
that implies an operational certificate lifecycle the project cannot support.
No certificates, CA service or TLS client-auth implementation are introduced by M11C.

## Rationale

Real device authentication requires a trusted enrollment process, CA governance,
secure device keys, certificate issuance/renewal, revocation, trust-store changes
and fleet recovery. A simulator issuing certificates to itself would not establish
manufacturing identity or secure key custody. Those operational responsibilities
would substantially exceed this milestone's validation scope. Deferral keeps the
current guarantees reviewable without claiming production OEM PKI.
It also avoids adding certificate-distribution infrastructure and fleet-wide
observability/debugging costs without an operational owner.

## Consequences

The existing setup remains small and reproducible. Credential theft can still
impersonate one vehicle; bearer tokens can be replayed until expiry, with no
server-side revocation. TLS deployment remains mandatory outside isolated local
testing. Key rotation/revocation, hardware-backed storage and operator IAM are
unimplemented. The [threat model](../threat-model.md) records these limits.

Adopting mTLS later would change onboarding and connection authentication, while
vehicle UUID ownership checks and Ed25519 artifact verification remain necessary.
If bearer tokens remain alongside mTLS, bind/check their subject against the
authenticated certificate identity; accepting an arbitrary stolen bearer token
over any trusted client connection would not provide that binding automatically.

## Production Evolution

1. Define root/intermediate CA hierarchy, environment-separated trust anchors,
   issuance policies, device certificate profiles and operational ownership.
2. Enroll devices through a controlled manufacturing/service process. Generate and
   protect a unique per-device private key in appropriate secure hardware; establish
   how certified identity maps to the immutable vehicle UUID and how replacements
   are approved. Do not use VIN knowledge as enrollment proof.
3. Validate client chains, permitted usages, validity and revocation policy at a
   TLS boundary. Authenticate the server on the vehicle too. If a proxy terminates
   mTLS, secure its connection to the application and prevent client-supplied
   identity headers from bypassing validation. Propagate verified identity to the
   same ownership checks on heartbeat, poll and deployment status.
4. Operate certificate renewal/rotation with overlap and offline-device recovery,
   revocation distribution/enforcement, trust-store updates and root rollover.
   Define outage behavior and avoid locking out the fleet during CA/key incidents.
5. Separate device PKI from release signing. Protect release signing in an audited
   HSM/KMS-backed approval pipeline; distribute verification trust updates safely.
   Add operator IAM/RBAC and provisioning/signing audit controls independently.
6. Test fleet-scale renewal, revocation, expired/compromised devices, environment
   isolation and disaster recovery before relying on certificate authentication.

These are proposed production responsibilities, not implemented features. mTLS
alone would not establish honest device execution, freshness of signed releases,
anti-rollback policy, human authorization or protection from denial of service.
