# Heimdall

[![Heimdall CI](https://github.com/filip-labs/Heimdall/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/filip-labs/Heimdall/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

Heimdall is a connected-vehicle OTA control plane and fleet simulator built to explore reliable software delivery to software-defined vehicles.

## Overview

Heimdall has two main parts:

- A Spring Boot control plane that stores vehicle, release, deployment, event, and rollout state in PostgreSQL.
- A Go fleet simulator that runs many autonomous vehicle agents in one process.

The control plane exposes REST APIs for registering vehicles, receiving heartbeats, creating software releases, deploying releases to vehicles, and orchestrating staged fleet rollouts. The simulator registers deterministic VINs, sends heartbeats, polls for active deployments, downloads OTA artifacts, verifies SHA-256 checksums, and reports deployment status back to the API.

## Why Heimdall Exists

Deploying vehicle software is not simply uploading a file. An OTA platform must account for intermittent connectivity, durable state, integrity checks, resumability, retries, staged rollout, blast radius control, health gates, and auditability.

Heimdall models those concerns in a compact project that is suitable for engineering discussion, local experimentation, and portfolio review.

## Current Architecture

```mermaid
flowchart TD
    SIM[Go Fleet Simulator] -->|HTTP| API[Spring Boot Control Plane]
    SIM -->|download| ART[OTA Artifact Endpoint]
    API -->|domain state + event intent| DB[(PostgreSQL + outbox)]
    DB --> PUB[Outbox Publisher]
    PUB --> K[Kafka]
    K --> CON[Control-plane Consumers]
    CON -->|re-read and lock| DB
    REC[Reconciliation Scheduler] -->|fallback| DB
    API --> MET[Prometheus / Grafana]
```

The Spring Boot application uses package-by-domain organization:

```text
src/main/java/dev/heimdall/
├── HeimdallApplication.java
├── deployment/
├── messaging/
├── release/
├── rollout/
└── vehicle/
```

The Go simulator stays in one Go package while splitting responsibilities across files:

```text
simulator/
├── artifact_downloader.go
├── client.go
├── config.go
├── fleet.go
├── main.go
├── ota.go
├── vehicle_agent.go
└── vin.go
```

## Core Domains

- `Vehicle`: registered vehicle identity, software version, connectivity status, and heartbeat timestamps.
- `SoftwareRelease`: release version, artifact URL, checksum, and metadata.
- `Deployment`: a single vehicle OTA attempt for one release.
- `DeploymentEvent`: immutable audit record of deployment status transitions.
- `Rollout`: orchestration record for deploying one release to a snapshot of eligible vehicles.
- `RolloutStage`: cumulative rollout stage such as 5%, 25%, 50%, or 100%.
- `RolloutTarget`: immutable rollout membership snapshot ordered by VIN.

## OTA Lifecycle

Deployments progress through this state machine:

```text
PENDING
↓
DOWNLOADING
↓
DOWNLOADED
↓
INSTALLING
↓
INSTALLED
```

A deployment can move to `FAILED` from any non-terminal state when artifact download, checksum verification, or another OTA step fails. Repeated reports of the current status are idempotent, and each real transition creates a deployment event.

## Reliability

Heimdall currently includes:

- SHA-256 artifact verification before install.
- Transient artifact download retry with exponential backoff.
- Retry only for request-level transient errors and HTTP 5xx gateway/server failures.
- Source software version snapshot on deployment creation.
- Idempotent status updates from vehicle agents.
- Deployment event audit trail.
- Vehicle restart/reuse by deterministic VIN.
- Staged rollout with target snapshots and health gates.
- Operator-controlled rollout pause, resume, and cancel.
- Pessimistic database locking for rollout stage advancement.

## Fleet Simulation

Run the simulator after the control plane is available:

```bash
cd simulator
FLEET_SIZE=100 go run .
```

Useful environment variables:

```text
HEIMDALL_URL=http://localhost:8080
FLEET_SIZE=100
INITIAL_SOFTWARE_VERSION=1.3.0
HEARTBEAT_INTERVAL=5s
DEPLOYMENT_POLL_INTERVAL=3s
SIMULATED_FAILURE_VINS=7FC00000000000003
```

The simulator generates deterministic VINs, registers or reuses existing backend vehicles, sends heartbeats, polls for active deployments, processes OTA artifacts, and shuts down cleanly on `SIGINT` or `SIGTERM`.

`SIMULATED_FAILURE_VINS` is an optional comma-separated VIN list for deterministic OTA installation failures. Targeted vehicles still download the artifact, verify its SHA-256 checksum, report `DOWNLOADED`, report `INSTALLING`, then report `FAILED` with `simulated installation failure` while keeping their previous software version.

## Staged Rollouts

Rollouts deploy one software release to the current eligible fleet in cumulative stages:

```text
5% -> 25% -> 50% -> 100%
```

For 100 target vehicles this means:

```text
5
+20
+25
+50
=100
```

Rollout targets are selected once at creation time from vehicles not already running the target release version, ordered by VIN ascending. Vehicles registered later are not added to the rollout.

Each stage creates deployments only for the incremental cohort. When all deployments in the current stage are terminal, Heimdall evaluates that stage's failure rate. If the failure rate is greater than the configured threshold, the current stage is marked `FAILED` and the rollout is `PAUSED`. If the failure rate is less than or equal to the threshold, the rollout advances to the next stage or completes.

Operators can pause, resume, or cancel a rollout with `POST /api/v1/rollouts/{id}/pause`, `POST /api/v1/rollouts/{id}/resume`, and `POST /api/v1/rollouts/{id}/cancel`. Pause and cancel stop future rollout progression without modifying existing deployments. Cancel is permanent and does not roll back already-installed vehicles.

Manual rollback is modeled as a new normal deployment with `POST /api/v1/deployments/{id}/rollback`. Heimdall uses the original deployment's `sourceSoftwareVersion` snapshot to find the previous `SoftwareRelease`, reuses that release's artifact URL and checksum, and leaves the original deployment and event history immutable.

```text
2.0.0
  -> deployment
2.1.0
  -> rollback deployment
2.0.0
```

Rollback deployments use the same OTA state machine, event trail, active-deployment protection, and vehicle software update behavior as any other deployment.

Automatic rollback is opt-in per rollout with `automaticRollbackEnabled`. When enabled, a health-gate failure still leaves the rollout `PAUSED` and the current stage `FAILED`; Heimdall then creates rollback deployments only for successfully installed deployments from that failed incremental stage. Failed installations, future stages, and vehicles that never received the bad release are untouched, and the rollout is not automatically resumed after recovery.

A manual demo can validate the health gate by configuring one canary to fail deterministically:

```bash
cd simulator
FLEET_SIZE=100 \
INITIAL_SOFTWARE_VERSION=2.0.0 \
SIMULATED_FAILURE_VINS=7FC00000000000003 \
go run .
```

With rollout stages `[5,25,50,100]` and a 5% failure threshold, VIN `7FC00000000000003` fails in the canary cohort, stage 0 pauses the rollout, and no deployments are created for stage 1.

## API Overview

Primary endpoint groups:

```text
/api/v1/vehicles
/api/v1/releases
/api/v1/deployments
/api/v1/rollouts
```

The Postman collection in `postman/Heimdall.postman_collection.json` covers the main vehicle, release, deployment, rollout, and health-check workflows. `postman/Local.postman_environment.json` targets `http://localhost:8080`.

## Technology Stack

- Java 21
- Spring Boot 4.1.1
- Maven
- PostgreSQL 17
- Flyway
- JPA/Hibernate
- Testcontainers
- Go
- Docker Compose
- Micrometer
- Prometheus
- Grafana
- Apache Kafka
- Postman

Redis is a roadmap item, not an implemented feature.

## Running Locally

Start PostgreSQL, Kafka, Prometheus, and Grafana:

```bash
docker compose up -d
```

The local database is exposed as:

```text
localhost:5433 -> container:5432
database: heimdall
username: heimdall
password: heimdall
```

Start the Spring Boot control plane:

```bash
./mvnw spring-boot:run
```

Start the simulator:

```bash
cd simulator
FLEET_SIZE=100 go run .
```

## Observability

The control plane exposes health, application information, and Prometheus
metrics through narrowly configured Actuator endpoints:

```text
http://localhost:8080/actuator/health
http://localhost:8080/actuator/info
http://localhost:8080/actuator/prometheus
```

Prometheus scrapes the host control plane every five seconds. Its local UI is
available at `http://localhost:9090`; the `heimdall` target should become `UP`
after the Spring Boot application starts.

Grafana is provisioned without manual setup at `http://localhost:3000`. Open
the `Heimdall / Heimdall Overview` dashboard to inspect:

- online and offline vehicles
- active and failed deployments
- running and paused rollouts
- committed OTA success/failure rates
- average time from deployment creation to its terminal outcome

The custom metrics use bounded `status` and `outcome` labels only. Generate
representative activity through the REST API or fleet simulator, then inspect
the Prometheus endpoint and dashboard. Stop the local services with:

```bash
docker compose down
```

## Event-Driven Control Plane

Kafka is Heimdall's internal responsive event backbone; PostgreSQL remains the
durable source of truth. Domain changes and event intent are written atomically
to a transactional outbox. A bounded publisher delivers JSON envelopes to
Kafka at least once, and idempotent consumers re-read PostgreSQL before they
trigger rollout evaluation or automatic rollback processing.

The Go simulator and vehicles continue to use HTTP only. Heartbeats are not
published because they are high-frequency and currently have no meaningful
asynchronous consumer.

The local KRaft broker listens on `localhost:9092`. Topics are created
automatically with three partitions and replication factor one:

- `heimdall.vehicle-events`
- `heimdall.deployment-events`
- `heimdall.rollout-events`

The normal event-driven path is backed by a slower 30-second reconciliation
scheduler, so a broker or consumer outage cannot permanently stall a rollout.
See [Event-Driven Control Plane](docs/event-driven-architecture.md) for event
contracts, ordering, delivery guarantees, consumers, and failure trade-offs.

Inspect the running broker without installing a local Kafka CLI:

```bash
# Topics
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list

# Deployment events
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic heimdall.deployment-events --from-beginning

# Consumer groups and offsets
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --all-groups --describe

# Pending/retried outbox rows
docker compose exec postgres psql -U heimdall -d heimdall -c \
  "SELECT event_type, attempt_count, created_at, last_error FROM event_outbox WHERE published_at IS NULL ORDER BY created_at;"
```

## Tests

Run the Java tests:

```bash
./mvnw test
```

Run the Go simulator tests:

```bash
cd simulator
go test ./...
go test -race ./...
```

## Project Structure

```text
.
├── compose.yaml
├── observability/
│   ├── grafana/
│   └── prometheus/
├── postman/
├── simulator/
├── src/main/java/dev/heimdall/
│   ├── deployment/
│   ├── messaging/
│   ├── release/
│   ├── rollout/
│   └── vehicle/
├── src/main/resources/
│   ├── application.yml
│   ├── db/migration/       # includes V10 transactional outbox
│   └── static/artifacts/
├── docs/
│   └── event-driven-architecture.md
└── src/test/java/dev/heimdall/
    ├── deployment/
    ├── integration/
    ├── messaging/
    └── rollout/
```

## Engineering Decisions

- PostgreSQL stores durable vehicle, deployment, event, and rollout state.
- Flyway migrations are immutable once created.
- Deployment state transitions are protected by the `Deployment` entity.
- Deployment creation captures the source software version before OTA begins.
- Manual and rollout-created deployments share the same deployment creation path.
- Rollouts snapshot target membership and persist deterministic VIN ordering.
- Rollout stage advancement uses pessimistic locking on the rollout row.
- Stage health is calculated against the current stage's incremental cohort.
- Artifact integrity is verified before install.
- Simulator retries are limited to transient request errors and retryable HTTP statuses.
- Fleet and control-plane gauges read current PostgreSQL state at scrape time.
- OTA outcome and duration metrics are recorded only after a terminal status transaction commits.

## Project Resources

- [Heimdall Roadmap](https://github.com/users/filip-labs/projects/4/views/1)
- [GitHub Issues](https://github.com/filip-labs/Heimdall/issues)
- [Postman API collection](postman/Heimdall.postman_collection.json)
- [Architecture overview](#current-architecture)

## Roadmap

```text
M1 Single Vehicle OTA                                Done
M2 Connectivity + Agent                              Done
M3 Artifact Integrity                                Done
M4 Reliability + Audit                               Done
M5 Fleet Simulator                                   Done
M6A Staged Fleet Rollouts                            Done
M6B Deterministic Failure Injection + Pause Demo     Done
M7A Manual Rollout Controls                          Done
M7B Rollback Deployments                             Done
M7C Automatic Rollback Policy                        Done
M8 Observability
M9 Kafka / Event-driven Architecture
M10 Scalability Testing
M11 Security
M12 Infrastructure + CI
M13 Operator Dashboard
M14 Documentation + Final Demo
```

## Scope

Heimdall is an engineering and portfolio simulation. It is not production automotive firmware infrastructure and does not implement the full safety, cybersecurity, certification, operational, or regulatory requirements of a real OEM OTA platform.
