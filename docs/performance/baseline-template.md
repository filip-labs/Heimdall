# M10B baseline evidence — `<profile> / <scenario> / <run>`

Copy this file for each warm-up or measured run, or paste the completed template
into issue #36. Do not mark warm-ups as measured runs.

## Identity

| Field | Value |
|---|---|
| Tested SHA | Actual `git rev-parse HEAD` |
| Executable baseline | `6699f04d6ea7d933cb4c161fd62b4e2745cd8caf`; protected paths unchanged |
| Phase / working-tree state | |
| Date/time/timezone | |
| Warm-up or measured | |
| Profile | `100` / `1000` / `5000` |
| Scenario | registration / heartbeat / polling / mixed / simulator / rollout |
| Run number | |
| RUN_ID | N/A for simulator/rollout |

## Host and tools

| Field | Value |
|---|---|
| CPU/model/core count | |
| RAM | |
| OS | |
| Kernel | |
| Java/JVM | |
| Docker and Compose | |
| k6 | |
| Go | |
| k6 on same host as backend? | yes / no |

## Workload configuration

| Field | Value |
|---|---|
| Ramp-up | |
| Steady duration | |
| Ramp-down | |
| Heartbeat interval | |
| Poll interval | |
| Setup timeout | |
| Initial software version | |
| Database reset immediately before run? | yes / no |
| Exact command | |

## k6 or simulator result

Report heartbeat and polling separately by `operation`, excluding `bootstrap`
and `vehicle_resolve` from steady-state conclusions.

| Signal | Value |
|---|---|
| Operation tag | |
| Total requests/registrations | |
| Theoretical requests/s | |
| Achieved requests/s | |
| p50 | |
| p95 | |
| p99 | |
| max | |
| HTTP failure rate | |
| Check failures | |
| Peak/achieved VUs | |
| Dropped iterations | |
| Client/connection errors | |
| Expected vehicle count | |
| Actual vehicle count | |

## Server and infrastructure evidence

| Signal | Peak/observed value |
|---|---|
| Backend CPU | |
| Backend RSS/memory | |
| JVM heap/non-heap | |
| GC pauses | |
| Hikari active/idle/pending/max/min | |
| Hikari acquire p95 | |
| PostgreSQL active/waiting connections | |
| PostgreSQL table rows/size/scans | |
| Outbox pending | |
| Outbox publish rate/failures | |
| Kafka consumer failures/lag | |
| Deployment creation rate | |
| Rollout evaluation p95 | |

## Load-generator evidence

| Signal | Value |
|---|---|
| k6/simulator CPU | |
| k6/simulator RSS/memory | |
| Open files/threads if relevant | |
| Cadence maintained? | |
| Generator saturation evidence | |

## Classification

Choose exactly one:

```text
HEALTHY
DEGRADED
BACKEND SATURATED
DATABASE SATURATED
CONNECTION POOL SATURATED
LOAD GENERATOR SATURATED
INCONCLUSIVE
```

Evidence supporting classification:

- 

## Anomalies and notes

- 

## Preserved artifacts

- k6 summary path:
- k6 sample path:
- screenshots/query exports:
- relevant logs:

## Hypotheses only

List possible explanations as `UNPROVEN`; do not propose or implement an
optimization in this phase.

- `UNPROVEN` —
