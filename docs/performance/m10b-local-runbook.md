# M10B local baseline runbook

This runbook prepares the authoritative pre-optimization baseline for issue
[#36](https://github.com/filip-labs/Heimdall/issues/36). Run it on the developer
machine. Do not change application, database, pool, scheduler, Kafka, outbox, or
simulator configuration between measured runs unless the change is explicitly
recorded and the affected suite is repeated.

## Automated local k6 runs (one terminal)

The runner automates evidence collection for #36; it produces no authoritative
performance classification. M10C has not started. The manual steps below remain
the fallback for debugging and for simulator/rollout sessions.

Prerequisites: Bash, Python **3.9+**, curl, Git, local Docker/Compose, Java **21**,
the Maven wrapper, k6 (with `k6/execution`), Go and `ps`. No Python packages are
required. macOS selects JDK 21 with `/usr/libexec/java_home -v 21`; Linux uses
`JAVA_HOME` or PATH and refuses other Java versions. Docker must use a local
Unix socket. The runner uses Compose project `heimdall` and this repository's
`compose.yaml`; stop any existing backend on port 8080 first. The observation
endpoints must become ready before load starts.

Start with a non-destructive preview (no tools/services started, no files written):

```bash
./scripts/m10b/run.sh --profile 100 --scenario heartbeat --label run1 --run-id 1222 --dry-run
```

A real command must explicitly acknowledge that **the local disposable Compose
PostgreSQL volume will be deleted**. This also resets any existing manual local
fleet state; preserve needed evidence/data first.

```bash
./scripts/m10b/run.sh --profile 100 --scenario polling --label run2 --run-id 1223 --reset-disposable-db
./scripts/m10b/run.sh --profile 100 --scenario mixed --label warmup --run-id 1224 --reset-disposable-db
./scripts/m10b/scenario.sh --profile 100 --scenario mixed --run-id-base 2200 --reset-disposable-db
```

`scenario.sh` runs warmup/run1/run2 with IDs base/base+1/base+2, resets the DB
before each, and writes a comparison with measured-run RPS/p95/p99 variation.
It stops on failure and never advances to another fleet size. It also accepts
`--dry-run`. Profile 5000 still runs two measured repetitions when explicitly
requested through this convenience command; use individual runs if host limits
make that impractical.

Supported scenarios map to the existing `registration.js`, `heartbeat.js`,
`deployment-polling.js` and `mixed-fleet.js`. A generated `entry.js` imports the
selected script, re-exports its options and delegates its workload functions.
Once per VU/operation it records `execution.scenario.startTime` in an observation
Gauge. This avoids guessing the steady-window origin from process start or the
first HTTP request; no requests, sleeps, stages or thresholds are changed.
The wrapper and empty explicit k6 config are retained for audit. User `K6_*`
option overrides are rejected and the user's default k6 config is not loaded.

Profile defaults are read from `load-tests/k6/lib/config.js` (and fail clearly if
its literal structure changes):

| Profile | Ramp-up | Steady | Ramp-down | Setup timeout |
|---|---|---|---|---|
| 100 | 30s | 2m | 15s | 2m |
| 1000 | 2m | 5m | 30s | 5m |
| 5000 | 5m | 10m | 1m | 10m |

`RAMP_UP`, `STEADY_DURATION`, `RAMP_DOWN`, `SETUP_TIMEOUT`,
`HEARTBEAT_INTERVAL`, `POLL_INTERVAL` and `SOFTWARE_VERSION` can be explicitly
overridden through the environment; all effective values are recorded.
Heartbeat/polling defaults remain 15s/10s. The runner is local-only and rejects
remote `BASE_URL`. `--sample-interval 15` and `--startup-timeout 180` control
observation spacing and readiness bounds. Sampling commands have their own
bounded timeouts; collection time adds to the interval, and every sample is
timestamped. It is not a real-time profiler.

Every run requires a numeric 1–4 digit RUN_ID. Automation reserves IDs across
phases under ignored `load-tests/results/.m10-run-ids/`; `1` and `0001` are the
same namespace. Failed runs retain their reservation. Choose new IDs even when
retrying. This ledger cannot detect IDs used by old manual runs; fresh DB reset
still occurs before every automated workload.

`--phase m10b` is the default and enforces unchanged committed **and local**
executable baseline paths (`src`, `simulator`, `load-tests/k6`, `pom.xml`,
`compose.yaml`). `--phase m10d` records those differences without enforcing
M10A equality, so the same runner can collect before/after evidence later.
Both phases also reject staged, unstaged or untracked local changes under
`scripts/m10b` (including dry-run). Commit or restore tooling changes before a
run; unrelated documentation edits do not block it. Metadata records
`orchestration_local_state` and `orchestration_integrity`. Ignored Python cache
files are not source changes. Selecting M10D does not automatically begin
optimization work.

Artifacts are unique, local and Git-ignored:

```text
load-tests/results/m10b/<UTC-time>-<profile>-<scenario>-<label>-<unique-suffix>/
  metadata.json           # identity, host/tool versions, effective config, exact command
  entry.js, k6-config.json
  lifecycle.log, backend.log, k6.log
  k6-summary.json, k6-samples.json
  resources.csv           # owned Spring JVM (not Maven), k6, four containers
  metrics.csv, prometheus.txt  # timestamped Hikari/JVM/GC/app series and full raw scrape
  postgres-activity.txt, postgres-tables.txt
  database.txt, kafka-consumer-groups.txt
  analysis.json, report.md
```

Existing manual result files are never overwritten. Interrupted/failed runs
keep partial evidence and a report; artifacts for steps not reached may be
absent. Ctrl+C stops the owned k6/backend process groups and waits for bounded
monitor commands. Compose stays up for inspection; no volume is deleted during
cleanup. The next explicitly requested run resets it. A filesystem lock blocks
concurrent automation in this checkout. Do not run manual workloads or runners
from other checkouts against the same Compose project concurrently.

The report separates setup, registration, heartbeat and deployment polling.
Recurring RPS uses the half-open steady interval `[scenario start + ramp-up,
scenario start + ramp-up + steady)`. Request timestamps denote completion;
latencies use linear percentile interpolation. Missing markers or incomplete
windows are explicitly flagged, never replaced with guessed full-test RPS.
Failures include unexpected operation-specific HTTP status (including transport
status 0). k6 checks, VUs and dropped-iteration samples are retained separately.
Registration reports counts and latency, never sustained throughput. All four
scenarios validate expected vehicle and distinct-VIN counts after completion.

Missing optional metrics and monitor failures are reported. Raw Prometheus
counter samples support later GC/outbox rate calculations. Docker CPU >100%
is valid across cores. All reports start **INCONCLUSIVE / REVIEW_REQUIRED**;
review k6 thresholds, cadence deviations, errors, generator pressure and
correlated backend/DB/pool evidence before choosing a classification listed in
section 10. No automatic saturation conclusion is made, and a Kafka spike or
latency increase alone is insufficient.

Rebuild a report offline with:

```bash
python3 scripts/m10b/analyze.py load-tests/results/m10b/<run-directory>
python3 -m unittest discover -s scripts/m10b/tests -v
npm --prefix load-tests test
```

Go simulator and healthy rollout remain manual sections 6–7: their runbook
uses an indefinite agent session and interactive rollout observation, without
a defined stop/measurement window. Automating those as if they were bounded
k6 runs would invent a benchmark contract. No new backend API is introduced.
Linux and macOS share the orchestration; host inventory differs (`/proc` versus
`sysctl`). `ps` CPU is its platform-specific process average, not an identical
instantaneous counter across OSes. Use matched hosts/configuration for M10D.

## 1. Verify the immutable baseline

```bash
git switch main
git pull --ff-only
git diff --exit-code 6699f04d6ea7d933cb4c161fd62b4e2745cd8caf..HEAD -- \
  src simulator load-tests/k6 pom.xml compose.yaml
git status --short -- src simulator load-tests/k6 pom.xml compose.yaml
git status --short
```

Stop if executable paths differ from the baseline, including staged, unstaged
or untracked executable files. HEAD may include documentation/orchestration
commits after `6699f04` (for example `4ed2a13`); record the actual tested SHA and
working-tree state. Record the environment:

```bash
mkdir -p load-tests/results
date --iso-8601=seconds
lscpu | sed -n '1,25p'
free -h
cat /etc/os-release
uname -a
java -version
docker version
docker compose version
k6 version
go version
git rev-parse HEAD
```

If k6 is missing on Debian/Ubuntu, install it from
[Grafana's official package repository](https://grafana.com/docs/k6/latest/set-up/install-k6/),
then re-run `k6 version`:

```bash
curl -fsSL https://dl.k6.io/key.gpg | \
  sudo gpg --dearmor -o /usr/share/keyrings/k6-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | \
  sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update
sudo apt-get install k6
k6 version
```

## 2. Start the stack

Terminal 1:

```bash
docker compose up -d
docker compose ps
docker compose exec postgres pg_isready -U heimdall -d heimdall
```

Terminal 2:

```bash
./mvnw spring-boot:run
```

Verify all observation endpoints before applying load:

```bash
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:8080/actuator/prometheus >/dev/null
curl --fail http://localhost:9090/-/ready
curl --fail http://localhost:3000/api/health
curl --get --fail http://localhost:9090/api/v1/query \
  --data-urlencode 'query=up{job="heimdall"}'
```

- Prometheus: <http://localhost:9090>
- Grafana: <http://localhost:3000>
- Dashboard: `Heimdall / Heimdall Overview`

## 3. Reset policy

Use a fresh disposable database before every warm-up or measured scenario when
comparing independent runs. This keeps vehicle/deployment table size and VIN
state comparable. Stop the backend first, then run:

```bash
docker compose down -v
docker compose up -d
docker compose exec postgres pg_isready -U heimdall -d heimdall
./mvnw spring-boot:run
```

The last command stays in its terminal. Never run `down -v` where data must be
retained. A fresh numeric `RUN_ID` is still required for every k6 execution.

For 100 and 1,000, use one warm-up and two measured runs per important
scenario. A third measured run is useful when the first two differ materially.
For 5,000, begin with one warm-up and one measured run; record honestly if host
limits prevent repetition. Run profiles in this order: 100, 1,000, then 5,000.

Within each profile use this order:

1. registration
2. heartbeat
3. deployment polling
4. mixed fleet
5. Go simulator
6. healthy staged rollout

## 4. k6 evidence convention

Raw results are local and ignored by Git. Keep one JSON summary and, when
per-operation analysis is required, one line-delimited sample file:

```text
load-tests/results/<profile>-<scenario>-<warmup|runN>-summary.json
load-tests/results/<profile>-<scenario>-<warmup|runN>-samples.json
```

`bootstrap` and `vehicle_resolve` are setup traffic. Report heartbeat and poll
results only from `operation=heartbeat` and `operation=deployment_poll` samples.
In mixed runs, never combine the two endpoint latency distributions.

Watch the k6 process in a separate terminal while every 1,000/5,000 run is
active:

```bash
K6_PID=$(pgrep -n k6)
pidstat -p "$K6_PID" 1
ps -o pid,%cpu,%mem,rss,vsz,command -p "$K6_PID"
```

Also record k6 `vus`, `vus_max`, `dropped_iterations`, client/connection errors,
and whether the intended cadence was maintained.

## 5. Official 100-profile commands

The first execution of each scenario is a warm-up. Reset the disposable stack,
restart the backend, and choose a new `RUN_ID` before each subsequent command.
Use the same durations for warm-up and measured runs unless the deviation is
recorded.

```bash
PROFILE=100 RUN_ID=1201 \
RAMP_UP=30s STEADY_DURATION=2m RAMP_DOWN=15s \
k6 run \
  --summary-export load-tests/results/100-registration-run1-summary.json \
  --out json=load-tests/results/100-registration-run1-samples.json \
  load-tests/k6/registration.js

PROFILE=100 RUN_ID=1202 \
RAMP_UP=30s STEADY_DURATION=2m RAMP_DOWN=15s \
HEARTBEAT_INTERVAL=15s SETUP_TIMEOUT=2m \
k6 run \
  --summary-export load-tests/results/100-heartbeat-run1-summary.json \
  --out json=load-tests/results/100-heartbeat-run1-samples.json \
  load-tests/k6/heartbeat.js

PROFILE=100 RUN_ID=1203 \
RAMP_UP=30s STEADY_DURATION=2m RAMP_DOWN=15s \
POLL_INTERVAL=10s SETUP_TIMEOUT=2m \
k6 run \
  --summary-export load-tests/results/100-polling-run1-summary.json \
  --out json=load-tests/results/100-polling-run1-samples.json \
  load-tests/k6/deployment-polling.js

PROFILE=100 RUN_ID=1204 \
RAMP_UP=30s STEADY_DURATION=2m RAMP_DOWN=15s \
HEARTBEAT_INTERVAL=15s POLL_INTERVAL=10s SETUP_TIMEOUT=2m \
k6 run \
  --summary-export load-tests/results/100-mixed-run1-summary.json \
  --out json=load-tests/results/100-mixed-run1-samples.json \
  load-tests/k6/mixed-fleet.js
```

Expected steady-state request rates are approximately:

```text
heartbeat: 100 / 15 s = 6.67 requests/s
polling:   100 / 10 s = 10 requests/s
mixed:     both rates above, reported separately
```

Copy the commands for measured run 2/3, changing only `RUN_ID` and output file
suffix. For 1,000 and 5,000 use `PROFILE=1000` / `PROFILE=5000`, profile setup
timeouts `5m` / `10m`, and the profile-default ramp durations unless a recorded
host constraint requires otherwise.

After registration or scenario setup, verify the database fleet size matches
the intended clean-environment profile:

```bash
docker compose exec postgres psql -U heimdall -d heimdall -c \
  "SELECT count(*) AS vehicle_count, count(DISTINCT vin) AS unique_vins FROM vehicle;"
```

## 6. Go simulator baseline

After a clean reset, run in its own terminal:

```bash
cd simulator
HEIMDALL_URL=http://localhost:8080 \
FLEET_SIZE=100 \
INITIAL_SOFTWARE_VERSION=1.3.0 \
HEARTBEAT_INTERVAL=5s \
DEPLOYMENT_POLL_INTERVAL=3s \
go run .
```

Record `fleet ready`, bootstrap failures, request failures, simulator CPU/RAM,
and achieved cadence. Repeat with 1,000 and, if practical, 5,000 without
changing simulator implementation. The theoretical rates are `fleetSize / 5`
heartbeats/s and `fleetSize / 3` polls/s.

## 7. Healthy staged rollout baseline

Keep the 100-vehicle simulator running. In another terminal create the existing
2.0.0 release and a healthy rollout:

```bash
RELEASE_ID=$(curl --silent --fail \
  -H 'Content-Type: application/json' \
  -d '{
    "version":"2.0.0",
    "description":"M10B healthy rollout baseline",
    "artifactUrl":"http://localhost:8080/artifacts/2.0.0.bin",
    "checksum":"cc9a283f29bab9173aee9f69d75e7d4a9a8f3b34ac1d969f60ea4b227c541d81"
  }' \
  http://localhost:8080/api/v1/releases | jq -r '.id')

ROLLOUT_ID=$(curl --silent --fail \
  -H 'Content-Type: application/json' \
  -d "{
    \"releaseId\":\"$RELEASE_ID\",
    \"stages\":[5,25,50,100],
    \"failureThresholdPercent\":5,
    \"automaticRollbackEnabled\":false
  }" \
  http://localhost:8080/api/v1/rollouts | jq -r '.id')

watch -n 2 "curl -s http://localhost:8080/api/v1/rollouts/$ROLLOUT_ID; echo; curl -s http://localhost:8080/api/v1/rollouts/$ROLLOUT_ID/stages"
```

Record creation rate, stage transition timestamps, rollout evaluation p95,
Hikari/DB pressure, outbox pending/publication, Kafka failures, and consumer
group offsets. Do not enable failure injection for this primary healthy run.

## 8. Prometheus queries

Run queries in the Prometheus UI or through:

```bash
prom_query() {
  curl --silent --get http://localhost:9090/api/v1/query \
    --data-urlencode "query=$1" | jq .
}
```

HTTP request rate and latency by backend route/status:

```promql
sum by (method, uri, status) (rate(http_server_requests_seconds_count[2m]))

histogram_quantile(0.95,
  sum by (le, method, uri) (rate(http_server_requests_seconds_bucket[2m])))

histogram_quantile(0.99,
  sum by (le, method, uri) (rate(http_server_requests_seconds_bucket[2m])))
```

k6's `operation` tag exists in k6 result samples, not Spring's HTTP metrics.
Use the tagged k6 sample output for heartbeat/poll separation and backend
`uri` labels as the independent server-side view.

Hikari:

```promql
hikaricp_connections_active
hikaricp_connections_idle
hikaricp_connections_pending
hikaricp_connections_max
hikaricp_connections_min
rate(hikaricp_connections_acquire_seconds_count[2m])
hikaricp_connections_acquire_seconds_max
```

JVM/process (confirm exact available names at `/actuator/prometheus`):

```promql
process_cpu_usage
system_cpu_usage
jvm_memory_used_bytes
jvm_memory_max_bytes
jvm_threads_live_threads
rate(jvm_gc_pause_seconds_count[2m])
```

Discover the actual process/JVM names exposed by the running Micrometer version:

```bash
curl -s http://localhost:8080/actuator/prometheus | \
  grep -E '^(process_|system_|jvm_memory_|jvm_threads_|jvm_gc_)' | \
  cut -d'{' -f1 | sort -u
```

Use process RSS from `ps`/`pidstat` when no process-memory gauge is exported;
do not invent a Prometheus series.

Outbox/Kafka:

```promql
heimdall_outbox_pending
sum by (aggregate) (rate(heimdall_outbox_published_total[2m]))
rate(heimdall_outbox_publish_failures_total[2m])
rate(heimdall_kafka_consumer_failures_total[2m])
```

Discover version-dependent Kafka client series before selecting them:

```bash
curl -s http://localhost:8080/actuator/prometheus | \
  grep -E '^(kafka_|heimdall_kafka_)' | cut -d'{' -f1 | sort -u
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --all-groups --describe
```

Rollout/deployment creation:

```promql
rate(heimdall_deployments_created_total[2m])

histogram_quantile(0.95,
  sum by (le) (rate(heimdall_rollout_evaluation_duration_seconds_bucket[2m])))

rate(heimdall_rollout_evaluation_duration_seconds_count[2m])
```

## 9. PostgreSQL and system evidence

```bash
docker compose exec postgres psql -U heimdall -d heimdall -c \
  "SELECT state, wait_event_type, wait_event, count(*) FROM pg_stat_activity WHERE datname = 'heimdall' GROUP BY 1,2,3 ORDER BY 4 DESC;"

docker compose exec postgres psql -U heimdall -d heimdall -c \
  "SELECT relname, n_live_tup, n_dead_tup, seq_scan, seq_tup_read, idx_scan, idx_tup_fetch FROM pg_stat_user_tables ORDER BY n_live_tup DESC;"

docker compose exec postgres psql -U heimdall -d heimdall -c \
  "SELECT relname, pg_size_pretty(pg_total_relation_size(relid)) AS total_size FROM pg_catalog.pg_statio_user_tables ORDER BY pg_total_relation_size(relid) DESC;"

docker stats heimdall-postgres heimdall-kafka heimdall-prometheus heimdall-grafana
pidstat -p "$(pgrep -f 'dev.heimdall.HeimdallApplication' | head -1)" 1
```

Do not enable `pg_stat_statements` during M10B. Record query-level diagnostics
as a possible M10C investigation only if runtime evidence justifies it.

## 10. Classification and stop rule

Classify each profile/scenario as exactly one of:

- `HEALTHY`
- `DEGRADED`
- `BACKEND SATURATED`
- `DATABASE SATURATED`
- `CONNECTION POOL SATURATED`
- `LOAD GENERATOR SATURATED`
- `INCONCLUSIVE`

Latency alone is insufficient evidence of saturation. Correlate it with CPU,
memory, Hikari pending/acquire time, PostgreSQL waits, errors, dropped
iterations, and generator resource usage. Record findings only; do not optimize
or start M10C.
