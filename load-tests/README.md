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

The heartbeat, polling, and mixed scenarios bootstrap their fleet before the
timed scenario. Reusing a `RUN_ID` intentionally fails fast instead of silently
measuring conflict responses. The harness has only broad safety thresholds
(less than 2% failed requests and p99 below 5 seconds); milestone M10B must
record observed throughput, p95, p99, error rate, and saturation before any
performance target is tightened.

Validate scripts without generating load:

```bash
for script in load-tests/k6/*.js; do k6 inspect "$script"; done
```

See [Scalability and load testing](../docs/scalability-and-load-testing.md) for
measurement queries, resource monitoring, cleanup, and interpretation.
