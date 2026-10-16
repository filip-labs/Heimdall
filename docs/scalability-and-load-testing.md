# Scalability and load testing

M10 uses a measure-first workflow: establish a reproducible baseline, identify
evidence-backed bottlenecks, make one targeted change at a time, then re-run the
same workload. M10A supplies instrumentation and workloads only; it does not
claim capacity results or tune production behavior.

## Baseline identity

The M10A source baseline is commit
`44186a91681ae848c5efb352532a01941f435469`. Before M10B, record the exact merged
M10A commit, JVM version, container image versions, host CPU/memory, k6 host,
profile, durations, and all environment overrides in issue #36. Results without
that identity are not comparable.

## Workloads

The scripts and configuration are documented in
[`load-tests/README.md`](../load-tests/README.md). Run the load generator on a
separate host for meaningful capacity measurements where possible. The mixed
workload intentionally runs independent heartbeat and polling loops, matching
the Go agent's two cadences. Both loops map their VUs onto the same deterministic
fleet; they do not create two logical fleets.

Fleet bootstrap is tagged `operation=bootstrap` and returns only minimal run
metadata. Each VU then resolves its one deterministic VIN once with
`operation=vehicle_resolve`. Steady-state requests use `operation=heartbeat`
or `operation=deployment_poll`. Filter by those operation tags when reporting
endpoint throughput, latency, and errors. A deployment poll returning `204`
means the vehicle has no active deployment and is a successful idle-fleet
response, not an error.

The profile-aware setup timeouts (`2m`, `5m`, and `10m`) are harness safety
bounds, not measured Heimdall capacity. Heartbeat and polling environment
durations are parsed into numeric seconds before k6 `sleep()`; executor stage
durations remain native k6 duration strings.

## Metrics

Prometheus scrapes `/actuator/prometheus`. Existing Spring Boot/Micrometer
binders provide JVM, process, HTTP server, HikariCP, and Kafka client metrics.
M10A adds only the two measurements not available from those binders:

- `heimdall_deployments_created_total`: committed deployment creation throughput
- `heimdall_rollout_evaluation_duration_seconds_*`: rollout evaluation latency

HTTP histograms are enabled so p95/p99 can be calculated by route. Useful
PromQL examples:

```promql
sum(rate(http_server_requests_seconds_count[5m])) by (method, uri, status)

histogram_quantile(0.95,
  sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))

histogram_quantile(0.99,
  sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))

rate(heimdall_deployments_created_total[5m])

histogram_quantile(0.95,
  sum by (le) (rate(heimdall_rollout_evaluation_duration_seconds_bucket[5m])))

hikaricp_connections_active
hikaricp_connections_idle
hikaricp_connections_pending

rate(heimdall_outbox_published_total[5m])
heimdall_outbox_pending
rate(heimdall_kafka_consumer_failures_total[5m])

process_cpu_usage
process_memory_usage_bytes
jvm_memory_used_bytes
```

Kafka client metric names depend on the client/binder version; discover the
available series before a run:

```bash
curl -s http://localhost:8080/actuator/prometheus | grep -E '^(kafka_|heimdall_kafka_)'
```

## Database and host checks

Inspect PostgreSQL activity and table growth during and immediately after a run:

```bash
docker compose exec postgres psql -U heimdall -d heimdall -c \
  "SELECT state, wait_event_type, wait_event, count(*) FROM pg_stat_activity WHERE datname = 'heimdall' GROUP BY 1,2,3 ORDER BY 4 DESC;"

docker compose exec postgres psql -U heimdall -d heimdall -c \
  "SELECT relname, n_live_tup, n_dead_tup, seq_scan, idx_scan FROM pg_stat_user_tables ORDER BY n_live_tup DESC;"

docker stats heimdall-postgres heimdall-kafka heimdall-prometheus heimdall-grafana
```

If the application runs outside Compose, record its process/container CPU and
memory separately. Capture k6 host CPU, memory, open files, network, achieved
iteration rate, and dropped iterations too. For a local load generator, use the
host process monitor (`top`, `htop`, or equivalent); for containerized k6, add
its container name to `docker stats`.

## Interpreting saturation

Do not label every latency rise a backend bottleneck. A load-generator ceiling
usually appears as saturated k6 CPU, insufficient VUs, dropped iterations, or
client-side connection errors while backend CPU, Hikari pending connections,
and database waits remain low. Backend saturation is supported by correlated
server evidence such as rising HTTP latency/errors together with CPU pressure,
Hikari pending connections, PostgreSQL waits, scheduler latency, or queue
growth. Repeat suspicious runs from a separate, larger load-generator host.

## Cleanup

Use a dedicated database for load tests when possible. Deterministic VINs start
with `7K6`; remove load data only from an explicitly disposable environment.
For a full local reset:

```bash
docker compose down -v
docker compose up -d
```

This deletes the local Compose PostgreSQL volume. Never run it against an
environment whose data must be retained.
