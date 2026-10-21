# M10D capacity re-test — prepared, not executed

Issue [#35](https://github.com/filip-labs/Heimdall/issues/35), parent
[#12](https://github.com/filip-labs/Heimdall/issues/12). M10C tuning stops here.
M10D repeats the M10B scenarios; it is not a 5105 tuning experiment. All results
in [the final-report skeleton](../scalability.md) remain pending.

## Configuration and startup

Use the same local Mac, JDK 21, Docker resources, database reset policy and
software versions as the retained M10B evidence where possible. Record every
remaining difference. The opt-in Spring `loadtest` profile sets only:

```yaml
server:
  tomcat:
    max-connections: 12000
    mbeanregistry:
      enabled: true
```

The capacity value is justified for this measured environment and 10000-VU
mixed workload, not as a universal production optimum. Normal application.yml
is unchanged (Tomcat connection default 8192). Worker threads remain default
200, Hikari maximum remains default 10. No DB/Kafka/JVM/OS/application-code
change is included. MBeans provide observation, not additional worker capacity.
These names/defaults were checked against the project's Spring Boot 4.1.1
Tomcat configuration metadata and property binding.

Manual backend startup (after preparing the same disposable Compose state):

```bash
cd /Users/filip_macbook/heimdall
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--spring.profiles.active=loadtest" \
  2>&1 | tee /tmp/heimdall-m10d-backend.log
```

Do not supply the old max-connections or MBean flags. Remove conflicting
SPRING_*, SERVER_*, JVM/Maven property overrides before a measured run; retain
any required environment settings in the manifest. Preserve the backend log
showing the active `loadtest` profile. The lightweight LoadtestProfileTest
loads profile-aware ConfigData and binds the real Boot Tomcat/Hikari classes
without starting services: loadtest resolves 12000/200/10 and MBeans=true;
normal configuration resolves 8192/200/10 and MBeans=false.

For a manually started backend, before any workload:

```bash
mkdir -p load-tests/results/m10d/precheck
python3 scripts/m10c/collect.py --preflight \
  > load-tests/results/m10d/precheck/preflight.json \
  2> load-tests/results/m10d/precheck/preflight.log
curl --fail --silent http://localhost:8080/actuator/prometheus \
  > load-tests/results/m10d/precheck/prometheus.txt
```

Use a fresh precheck directory for subsequent sessions. Require successful
preflight and verify maxConnections=12000, maxThreads=200 and the Prometheus
`hikaricp_connections_max` value 10. Stop on mismatch; the generic preflight
checks readability/identity, not these hardcoded expected capacity values.
No JFR is required for normal M10D runs. The retained M10C runner only requires
JFR for historical RUN_ID=5104; do not reuse that ID.

## Baseline-equivalent matrix

Use original registration.js, heartbeat.js, deployment-polling.js and
mixed-fleet.js through the M10B automation with `--phase m10d`. Do not substitute
mixed-fleet-diagnostic.js: bounded resolution retries would change the workload.
Report the original resolution failure behavior if it recurs.

| Fleet profile | k6 scenarios, in order | Ramp / steady / down | Setup timeout | Repetitions |
|---|---|---|---|---|
| 100 | registration, heartbeat, polling, mixed | 30s / 2m / 15s | 2m | warmup, run1, run2 |
| 1000 | registration, heartbeat, polling, mixed | 2m / 5m / 30s | 5m | warmup, run1, run2 |
| 5000 | registration, heartbeat, polling, mixed | 5m / 10m / 1m | 10m | warmup, run1; run2 if practical |

All k6 heartbeat/poll intervals remain 15s/10s, initial software version 1.0.0.
Mixed uses two scenario populations: profile 5000 means up to 10000 VUs sharing
5000 vehicles. Registration is a count/latency scenario, not sustained RPS.
Compare with each retained baseline run's effective configuration; if it differs
from this matrix, reproduce its actual settings or label the comparison unmatched.
Do not shorten runs or change cadence/thresholds to obtain better results.

Reserve the following candidate namespaces only at execution time. First check
`load-tests/results/.m10-run-ids/` and manual evidence for collisions; failed IDs
must never be reused. Offsets 0/1/2 mean warmup/run1/run2; replacements must be
fresh numeric IDs and recorded explicitly.

| Profile | registration base | heartbeat base | polling base | mixed base |
|---|---|---|---|---|
| 100 | 6100 | 6110 | 6120 | 6130 |
| 1000 | 6200 | 6210 | 6220 | 6230 |
| 5000 | 6300 | 6310 | 6320 | 6330 |

Automation starts its own backend and resets the disposable Compose DB for
every invocation. Stop the manual backend first; never run both concurrently.
The backend inherits `SPRING_PROFILES_ACTIVE=loadtest`; this is the equivalent
profile activation for the runner's fixed `./mvnw spring-boot:run` command.
Record that environment prefix in the evidence manifest: the existing runner
metadata does not have a dedicated active-Spring-profiles field. Verify active
profile in backend.log and effective limits in retained metrics. Do not use
`--phase m10b`, which correctly rejects the new executable configuration versus
its immutable baseline. M10D records those committed differences.

Exact example for one cell, not executed during preparation:

```bash
SPRING_PROFILES_ACTIVE=loadtest \
RAMP_UP=30s STEADY_DURATION=2m RAMP_DOWN=15s SETUP_TIMEOUT=2m \
HEARTBEAT_INTERVAL=15s POLL_INTERVAL=10s SOFTWARE_VERSION=1.0.0 \
./scripts/m10b/run.sh --phase m10d --profile 100 \
  --scenario registration --label warmup --run-id 6100 --reset-disposable-db
```

Repeat each cell individually with the table's scenario/profile/durations and
ID offset. Do not blindly loop across larger profiles: inspect host/backend
headroom and preceding results first. Each command deletes only the explicitly
acknowledged disposable local Compose database; preserve all prior evidence.
Use one warmup/two measured repetitions at 100/1000; at 5000 explicitly report
why a second measurement was impractical if omitted. Never compare warmup as
measured data. When runs disagree materially, report variability rather than
choosing the fastest result.

After k6 at each practical fleet size, repeat the baseline's **Go simulator**
and **healthy staged rollout** procedures from sections 6–7 of the
[M10B runbook](m10b-local-runbook.md#6-go-simulator-baseline), with this backend
profile and fresh disposable state. Keep simulator heartbeat 5s/poll 3s,
initial software 1.3.0, healthy release 2.0.0, stages 5/25/50/100, failure threshold 5%,
automatic rollback=false and no failure injection. Match the actual baseline's
observation window; those manual sessions are not bounded by the k6 durations.
Record simulator cadence/errors/resources, rollout transitions/evaluation and
deployment creation metrics. Explicitly mark unexecuted/unmatched cases.

## Evidence and acceptance

For every measurement retain tested SHA, source/local state, exact command and
environment, active Spring profiles, backend JVM flags, host/tool/container
versions and resource budgets, profile/scenario/RUN_ID, reset and warmup policy,
maxConnections/maxThreads/Hikari max and MBean status. Do not silently alter
Maven's existing JVM startup behavior between baseline and re-test.

Retain raw/summary k6, wrapper and scenario-start markers, console/backend logs,
exit code, throughput and separate heartbeat/poll rates, operation p50/p95/p99,
HTTP failures, checks, resolved/exhausted outcomes where available, and dropped
or interrupted iterations. Use marker-derived steady windows in the existing
analyzer; retain global metrics separately. No marker means an explicit
inconclusive window, not a guessed steady result.

Retain backend/load-generator/container CPU and memory, Hikari active/pending,
PostgreSQL states/waits/table sizes, Kafka consumer lag/outbox pending/published/
failures, rollout evaluation metrics and deployment creation counts/durations
where available. Metrics unavailable in a scenario must be marked unavailable
or not applicable. Do not invent zero values. Include monitor coverage/errors,
clock/timing limitations, host noise, run-to-run variability and bottleneck
classification. Compare full established load, not only successful survivors.

Before final M10D acceptance, run Java, Go and Go race, M10B/M10C tooling and JS
checks, and require GitHub CI green for the tested SHA. Publish actual results
and evidence references in docs/scalability.md after execution. Local Mac
measurements are not a production capacity guarantee. No M10D results, 5105
experiment or new tuning are authorized by this preparation document alone.
