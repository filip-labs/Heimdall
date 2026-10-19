# M10C Phase 1 mixed-workload diagnosis

This experiment compares the preserved M10B 5,000-client mixed evidence with an opt-in diagnostic
variant that prevents failed vehicle resolution from becoming an uncontrolled retry loop. It does
not modify the M10B scripts, backend behavior, or production configuration and does not claim a
capacity improvement.

## Code audit

| Area | Code-supported observation | Current status |
|---|---|---|
| Vehicle resolution | The baseline caches only a successful ID and throws before normal cadence sleep on failure. | Retry amplification is plausible and observed; primary causality is unproven. |
| Heartbeat | One transactional vehicle lookup followed by an entity update/flush. | Combined-load query/lock pressure is unproven. |
| Polling | A vehicle existence query precedes the active-deployment query. | Extra query cost is visible in code; material bottleneck impact is unproven. |
| Tomcat | No connector/thread tuning exists in `application.yml`. | Connector, thread, and socket limits remain hypotheses. |
| Hikari | No project override is present; M10B observed no pending connections in the failing mixed run. | Not supported as the primary mixed-run bottleneck; separately observed under simulator + rollout. |
| Metrics | Existing Actuator Prometheus endpoint exposes HTTP, JVM, process, Hikari, and runtime-dependent Tomcat binders. | The collector records actual Tomcat series and reports missing ones rather than inventing them. |

## Diagnostic behavior

Each VU still maps onto the same deterministic fleet and caches a successful vehicle ID. A failed
resolve waits for `DIAGNOSTIC_RESOLVE_BACKOFF` and can be attempted at most
`DIAGNOSTIC_RESOLVE_MAX_ATTEMPTS` times (defaults: `5s`, `3`). An exhausted VU sleeps at its normal
heartbeat/poll cadence and never tight-loops. Custom counters report attempts, failures, retries,
and exhausted VUs, tagged only by the bounded scenario name.

The original `load-tests/k6/mixed-fleet.js` remains the M10B baseline and is not changed.

## macOS experiment

Use the same machine and backend/Docker configuration as M10B. A fresh disposable database and a
fresh `RUN_ID` are required. The reset below deletes the local Heimdall Compose volume.

Terminal 1:

```bash
git switch m10c-phase1-diagnostics
docker compose -p heimdall down -v
docker compose -p heimdall up -d
docker compose -p heimdall exec postgres pg_isready -U heimdall -d heimdall
./mvnw spring-boot:run
```

Wait until `curl --fail http://localhost:8080/actuator/health` succeeds. Terminal 2:

```bash
PROFILE=5000 RUN_ID=5101 \
RAMP_UP=5m STEADY_DURATION=10m RAMP_DOWN=1m SETUP_TIMEOUT=10m \
HEARTBEAT_INTERVAL=15s POLL_INTERVAL=10s \
DIAGNOSTIC_RESOLVE_MAX_ATTEMPTS=3 DIAGNOSTIC_RESOLVE_BACKOFF=5s \
scripts/m10c/run-mixed-diagnostic.sh
```

Do not rerun or overwrite the original M10B evidence. Compare its report with the new directory
under `load-tests/results/m10c/`.

## Evidence to return

Send the entire new result directory, plus:

- `k6-summary.json`, `k6-console.log`, `k6-exit-code.txt`, and `git-sha.txt`;
- counts for `m10c_vehicle_resolve_attempts`, `failures`, `retries`, and `exhausted` by scenario;
- heartbeat and `deployment_poll` achieved rates, p95/p99, failures, and dropped iterations;
- `runtime-samples.jsonl`, including every Prometheus scrape timeout;
- exposed/missing Tomcat thread and connection series;
- process CPU, JVM live threads, Hikari active/idle/pending/max;
- PostgreSQL activity/wait-state samples and container CPU/memory;
- backend log excerpts containing Tomcat/NIO/socket errors during the diagnostic window.

The next engineering review decides whether retry amplification materially changes the failure and
which backend hypothesis deserves the first controlled experiment. No tuning follows automatically.
