# M10B local baseline runbook

This runbook prepares the authoritative pre-optimization baseline for issue
[#36](https://github.com/filip-labs/Heimdall/issues/36). Run it on the developer
machine. Do not change application, database, pool, scheduler, Kafka, outbox, or
simulator configuration between measured runs unless the change is explicitly
recorded and the affected suite is repeated.

## 1. Verify the immutable baseline

```bash
git switch main
git pull --ff-only
test "$(git rev-parse HEAD)" = "6699f04d6ea7d933cb4c161fd62b4e2745cd8caf"
git status --short
```

Stop if the SHA differs or the worktree is not clean. Record the environment:

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
ps -o pid,%cpu,%mem,rss,vsz,nlwp,cmd -p "$K6_PID"
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
