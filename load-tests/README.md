# Heimdall k6 load harness

This harness measures the HTTP control plane. It complements the Go fleet
simulator rather than replacing it: k6 produces repeatable request-rate and
latency measurements, while the simulator exercises the longer-lived OTA
agent behavior, artifact verification, retries, and status transitions.

## Prerequisites

- Heimdall and its local dependencies are running.
- [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/) is installed.
- Each run uses a fresh numeric `RUN_ID` so deterministic VINs do not collide.

The built-in profiles are `100`, `1000`, and `5000`. Start with 100 and only
increase scale after checking backend and load-generator resource headroom.

```bash
PROFILE=100 RUN_ID=1001 k6 run load-tests/k6/registration.js
PROFILE=100 RUN_ID=1002 k6 run load-tests/k6/heartbeat.js
PROFILE=100 RUN_ID=1003 k6 run load-tests/k6/deployment-polling.js
PROFILE=100 RUN_ID=1004 k6 run load-tests/k6/mixed-fleet.js
```

Configuration is environment-based:

| Variable | Default | Purpose |
|---|---:|---|
| `BASE_URL` | `http://localhost:8080` | Heimdall base URL |
| `PROFILE` | `100` | Fleet size: `100`, `1000`, or `5000` |
| `RUN_ID` | `0001` | Numeric run namespace used in deterministic VINs |
| `SOFTWARE_VERSION` | `1.0.0` | Initial vehicle version |
| `RAMP_UP` | profile-specific | Ramp duration override |
| `STEADY_DURATION` | profile-specific | Steady-state duration override |
| `RAMP_DOWN` | profile-specific | Ramp-down duration override |
| `HEARTBEAT_INTERVAL` | `15s` | Per-vehicle heartbeat cadence |
| `POLL_INTERVAL` | `10s` | Per-vehicle deployment-poll cadence |
| `SETUP_TIMEOUT` | `2m` / `5m` / `10m` | Bootstrap safety bound for the 100 / 1,000 / 5,000 profile |

Heartbeat and polling intervals accept positive `ms`, `s`, `m`, or `h` values
such as `500ms`, `15s`, or `1m`. They are converted to numeric seconds before
being passed to k6 `sleep()`. Stage durations and `SETUP_TIMEOUT` remain k6
duration strings. Invalid and zero/negative intervals fail during script setup.

The heartbeat, polling, and mixed scenarios bootstrap their fleet before the
timed scenario. Reusing a `RUN_ID` intentionally fails fast instead of silently
measuring conflict responses. The harness has only broad safety thresholds
(less than 2% failed requests and p99 below 5 seconds); milestone M10B must
record observed throughput, p95, p99, error rate, and saturation before any
performance target is tightened.

Bootstrap returns only the small run identity, never the complete vehicle
response array. Each VU maps its global `__VU` through
`((__VU - 1) % fleetSize) + 1`, derives the deterministic VIN, and resolves that
single vehicle once through the by-VIN endpoint. This also makes the two mixed
scenarios share one logical fleet even when their global VU IDs occupy different
ranges.

Requests are tagged by `operation`:

- `bootstrap` and `vehicle_resolve` are setup traffic
- `registration`, `heartbeat`, and `deployment_poll` are measured workload traffic

Filter M10B results by the relevant steady-state `operation`; do not include
bootstrap or resolution traffic in heartbeat or polling throughput. Deployment
polling treats both `200` (active deployment) and `204` (no active deployment)
as successful API responses.

Short smoke commands, using a fresh `RUN_ID` for each script:

```bash
PROFILE=100 RUN_ID=1101 RAMP_UP=3s STEADY_DURATION=5s RAMP_DOWN=2s \
  k6 run load-tests/k6/registration.js
PROFILE=100 RUN_ID=1102 RAMP_UP=3s STEADY_DURATION=5s RAMP_DOWN=2s \
  k6 run load-tests/k6/heartbeat.js
PROFILE=100 RUN_ID=1103 RAMP_UP=3s STEADY_DURATION=5s RAMP_DOWN=2s \
  k6 run load-tests/k6/deployment-polling.js
PROFILE=100 RUN_ID=1104 RAMP_UP=3s STEADY_DURATION=5s RAMP_DOWN=2s \
  k6 run load-tests/k6/mixed-fleet.js
```

Validate scripts without generating load:

```bash
for script in load-tests/k6/*.js; do k6 inspect "$script"; done
npm --prefix load-tests test
```

See [Scalability and load testing](../docs/scalability-and-load-testing.md) for
measurement queries, resource monitoring, cleanup, and interpretation.
For official M10B measurements, follow the
[local baseline runbook](../docs/performance/m10b-local-runbook.md) and use the
[evidence template](../docs/performance/baseline-template.md). Raw files under
`load-tests/results/` are intentionally ignored by Git.
