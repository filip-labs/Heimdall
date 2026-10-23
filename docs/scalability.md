# Heimdall scalability report — M10D capacity re-test

M10D repeats the M10B fleet-scale scenarios after the single confirmed M10C
capacity change: an opt-in local/load-test Tomcat connection budget of 12,000.
The purpose of this report is to document the measured before/after behavior, not
to claim a universal production capacity.

## Executive summary

The M10C change removed the previously confirmed Tomcat admission bottleneck.
At the 5,000 mixed profile, Heimdall now admits the full 10,000 VUs and completes
vehicle resolution with zero HTTP failures instead of exhausting the previous
8,192-connection budget.

That improvement does **not** make the maximum mixed workload fully healthy.
At 10,000 mixed VUs, recurring Hikari connection-pool pressure remains, tail
latency rises into multi-second territory, achieved heartbeat/poll cadence falls
several percent below target, and one of the two measured repetitions crosses
the 5-second global p99 k6 threshold. The correct post-M10C classification is
therefore **DEGRADED**, not an unlimited-capacity success.

The lower profiles remain functionally healthy. The 100-vehicle simulator and
healthy staged rollout complete without failures or residual backlog. The
1,000-vehicle simulator and rollout preserve correctness but still reproduce
transient Hikari/outbox pressure; the backlog drains to zero after rollout.

## Environment and tested revision

- Repository: `filip-labs/Heimdall`
- M10D executable/tested SHA: `52b6131d055fcf9f4fab0fcba0e3051f2e95c807`
- Branch used for collection: `m10d-capacity-retest`
- Host class: local macOS developer machine using Docker Desktop
- Java: JDK 21
- Spring profile: `loadtest`
- Raw evidence: retained locally under `load-tests/results/m10d/` and excluded
  from Git by design
- Exact host/tool versions and per-run commands are retained in generated
  `metadata.json`, manual manifests and run logs.

The report commit itself is documentation-only. The executable revision above
is the benchmark identity and remains the reference when comparing M10D results.

## Methodology

The original M10B workload shape was preserved. M10D used the original k6
registration, heartbeat, deployment-polling and mixed-fleet scripts; the
diagnostic M10C mixed retry harness was not substituted.

| Profile | Ramp-up | Steady | Ramp-down | Setup timeout |
|---|---:|---:|---:|---:|
| 100 | 30 s | 2 min | 15 s | 2 min |
| 1,000 | 2 min | 5 min | 30 s | 5 min |
| 5,000 | 5 min | 10 min | 1 min | 10 min |

k6 heartbeat and poll intervals remained 15 s and 10 s respectively, with
software version `1.0.0`. Independent automated runs reset the disposable
PostgreSQL volume. Warmups were retained but were not substituted for measured
repetitions.

The manual Go simulator used the existing workload contract:

- heartbeat interval: 5 s
- deployment poll interval: 3 s
- initial software version: `1.3.0`

Healthy rollout re-tests targeted release `2.0.0` with stages
`5% -> 25% -> 50% -> 100%`, a 5% failure threshold and automatic rollback
disabled.

Latency values below refer to operation-specific steady windows where available.
Setup traffic such as bootstrap and vehicle resolution is kept separate from the
steady heartbeat/poll distributions.

## M10B baseline

The immutable M10A executable baseline is
`6699f04d6ea7d933cb4c161fd62b4e2745cd8caf`. The final M10B collection was
recorded on main SHA `fd819b74e5efa7643776f786cfc5be6b959a2bac` while
preserving that executable behavior.

The important M10B findings were:

- 100 profile: all required scenarios were **HEALTHY**.
- 1,000 k6 scenarios: **HEALTHY**.
- 1,000 simulator + rollout: transient Hikari saturation, with pool max/active
  10, pending peak 190 and max acquire about 2.589 s; correctness was preserved
  and pressure drained after rollout.
- 5,000 isolated registration, heartbeat and polling: **HEALTHY**.
- 5,000 mixed: **BACKEND SATURATED**. The intended mixed cadence was not
  sustained, heartbeat and polling were roughly 20-22% below target, and
  multi-second tail latency plus Actuator/Prometheus timeouts were observed.
  Failed lazy vehicle resolution retries amplified pressure after degradation
  began, so this was never presented as an exact production capacity boundary.

## M10C treatment

M10C confirmed the 8,192 Tomcat connection limit as a material admission
bottleneck. The controlled 12,000-connection configuration admitted all
10,000/10,000 VUs, eliminated the prior exhausted-VU/vehicle-resolution failure
mode and reduced HTTP failures to zero in the confirming diagnostic run.

M10D therefore uses this opt-in profile:

```yaml
server:
  tomcat:
    max-connections: 12000
    mbeanregistry:
      enabled: true
```

Normal `application.yml` remains unchanged. Tomcat worker threads remain at
their default 200 and Hikari remains at its default maximum of 10. M10D does not
add DB, Kafka, JVM, OS or business-logic tuning.

## M10D results

### Summary

| Profile | k6 scenarios | Simulator / rollout | Engineering classification |
|---|---|---|---|
| 100 | Registration, heartbeat, polling and mixed completed without the maximum-profile pressure symptoms | 100/100 reached 2.0.0; 0 failed deployments; final Hikari/outbox pending 0 | **HEALTHY** |
| 1,000 | Registration, heartbeat, polling and mixed remained within the expected lower-profile behavior | 1,000/1,000 reached 2.0.0; transient Hikari/outbox pressure; backlog drained to 0 | **DEGRADED during rollout pressure; correctness preserved** |
| 5,000 | Isolated workloads sustain target cadence with zero request failures; mixed admits all 10,000 VUs but develops repeated pool/tail-latency pressure | No matched 5,000 manual simulator/rollout baseline was available, so no new unmatched manual benchmark was invented | **DEGRADED at maximum mixed load** |

### 100 simulator and healthy rollout

The staged rollout completed successfully through all four stages. All 100
vehicles finished on software version `2.0.0`, with zero failed deployments.

At the final snapshot:

- Hikari pending: 0
- Hikari active/idle: 0 / 10
- Hikari connection timeouts: 0
- outbox pending: 0
- outbox publish failures: 0
- Kafka consumer failures: 0

The rollout was created at `2026-10-23T23:02:33.371420Z` and reached its final
completed update at `2026-10-23T23:02:53.012234Z`, about 19.6 seconds later.

Classification: **HEALTHY**.

### 1,000 simulator and healthy rollout

The rollout again completed all stages with zero failed deployments and all
1,000 vehicles reached software version `2.0.0`.

The observation sampler recorded:

- Hikari active peak: 10/10
- Hikari pending peak: 188
- non-zero Hikari pending samples: 3/27 (11.11%)
- outbox pending peak: 3,458
- post-rollout snapshot outbox pending: 1,195
- subsequent outbox pending: 0
- final Hikari pending: 0
- Hikari connection timeouts: 0
- outbox publish failures: 0
- Kafka consumer failures: 0

This is materially the same class of transient pool pressure found by M10B
(pending peak 190). M10D therefore does **not** claim that the Hikari bottleneck
was fixed. Correctness remained intact and all observed pressure drained after
the rollout.

Classification: **DEGRADED during transient connection-pool/outbox pressure;
correctness preserved**.

### 5,000 registration

Registration completed 5,000/5,000 vehicles with zero failures and the database
contained 5,000 unique VINs. No new correctness limit was found in isolated
registration.

Classification: **HEALTHY**.

### 5,000 heartbeat

Measured heartbeat repetitions sustained approximately the configured
333.33 requests/s target with zero request failures. Intermittent Hikari pending
bursts were observed, but they were sparse rather than sustained.

The important distinction is that the isolated heartbeat workload remains
serviceable after the M10C admission change; it does not reproduce the severe
mixed-workload degradation by itself.

Classification: **HEALTHY with intermittent pool contention observed**.

### 5,000 deployment polling

Polling continued to sustain the 500 requests/s target closely with zero
failures, while also showing intermittent Hikari queueing.

Representative retained evidence:

| Run | Steady rate | p95 | p99 | Hikari pending |
|---|---:|---:|---:|---|
| warmup | 498.80 req/s | 50.76 ms | 126 ms | peak 119; 4/57 sampled windows |
| run1 | 499.13 req/s | 59.25 ms | 131.8 ms | peak 98; 3/57 sampled windows |
| run2 | 498.95 req/s | 60.9 ms | 159.1 ms | peak 40; 1/57 sampled windows |

Throughput/correctness remain healthy, but the repeated pool queueing is a real
capacity signal rather than a reason to label the isolated endpoint saturated.

Classification: **DEGRADED at the connection-pool layer; target polling cadence
preserved**.

### 5,000 mixed fleet

This is the material before/after result.

All M10D mixed runs reached the full 10,000 VUs and recorded zero HTTP failures.
This directly contrasts with the M10B admission/exhaustion failure mode. However,
the workload develops recurring Hikari pressure and long-tail latency.

| Run | Heartbeat steady | Poll steady | Hikari pending | Outcome |
|---|---|---|---|---|
| warmup | p95 2.951 s, p99 4.719 s; cadence -2.90% | p95 2.794 s, p99 5.143 s; cadence -3.80% | peak 190; pending in 13/55 samples | thresholds passed |
| run1 | p95 3.083 s, p99 4.155 s; cadence -3.64% | p95 2.556 s, p99 3.977 s; cadence -3.85% | peak 190; pending in 10/55 samples | thresholds passed |
| run2 | p95 4.006 s, p99 5.611 s; cadence -4.17% | p95 4.221 s, p99 5.607 s; cadence -6.40% | peak 190 | global p99 threshold failed |

Run2 completed the workload with zero request failures and zero interrupted
iterations, but k6 exited 99 because the global `http_req_duration p(99)<5000`
threshold was crossed at approximately 5.48 seconds. The database correctness
check still passed with 5,000 unique vehicles.

The warmup and run1 also show that the Hikari peak is not a single isolated
sample: non-zero pending appeared in 23.64% and 18.18% of the respective sampled
windows, usually while the 10-connection pool was fully active.

Compared with M10B, the result is a clear improvement:

- full 10,000-VU admission instead of the prior 8,192-connection boundary
- zero vehicle-resolution/HTTP failures in the M10D mixed repetitions
- cadence loss reduced from roughly 20-22% to roughly 3-6%

But stable maximum-load latency is still not healthy:

- multi-second p95/p99 persists
- recurrent connection-pool contention remains
- one of two measured repetitions fails the 5-second global p99 threshold

Classification: **DEGRADED — full admission and correctness preserved, with
recurrent connection-pool pressure and unstable tail latency at maximum mixed
load**.

## Before/after conclusion

M10C successfully moved the first capacity boundary. The previous Tomcat
connection admission limit is no longer the dominant failure mode at the 5,000
mixed profile.

The next practical boundary is downstream of admission. The default
10-connection Hikari pool repeatedly queues work under the maximum mixed load,
and similar transient pressure is visible during the 1,000-vehicle staged
rollout. M10D intentionally does not tune that next variable because doing so
would turn the capacity re-test into another optimization experiment.

The defensible conclusion is therefore:

> On this local test environment, the opt-in 12,000 Tomcat connection budget
> removes the prior 10,000-VU admission failure and materially improves mixed
> workload correctness and achieved cadence. It does not establish a stable
> 10,000-VU production capacity: recurring database-connection contention and
> multi-second tail latency remain at the maximum mixed profile.

## Validation

Local validation observed during M10D includes:

- M10B tooling: 33/33 tests passed
- M10C tooling: 48/48 tests passed
- load-test JavaScript tests: 8/8 passed
- representative Kafka integration tests completed with zero failures/errors

Final acceptance still requires the repository CI to be green on the final PR
head. The benchmark identity remains executable SHA
`52b6131d055fcf9f4fab0fcba0e3051f2e95c807`; the final report commit is
documentation-only.

## Evidence inventory

Automated M10D evidence is retained under:

```text
load-tests/results/m10d/<UTC-time>-<profile>-<scenario>-<label>-<suffix>/
```

Each automated result directory can contain:

- `metadata.json`
- `report.md`
- `analysis.json`
- `k6-summary.json`
- `k6-samples.json`
- `resources.csv`
- `metrics.csv`
- timestamped Prometheus/PostgreSQL evidence
- backend, k6 and lifecycle logs

Key maximum-profile evidence includes:

- `2026-10-23T210224-5000-polling-run2-1677edc0`
- `2026-10-23T212404-5000-mixed-warmup-8cfdde24`
- `2026-10-23T215346-5000-mixed-run1-24370513`
- `2026-10-23T221647-5000-mixed-run2-0466c9bb`

The 100/1,000 simulator and rollout re-tests retain separate manual evidence
directories under the same M10D results root. Raw evidence remains local and
Git-ignored; the repository report records the reproducible procedure and the
engineering conclusions rather than committing large benchmark artifacts.

## Limitations and reproducibility

These results describe a local Mac/Docker test environment, not production
capacity. In particular:

- CPU percentages can exceed 100% for multi-core processes.
- Docker Desktop, the backend, k6, simulator and observability stack share the
  same host.
- metric sampling is sparse and can miss short spikes.
- the k6 workload is closed-loop, so long request latency can reduce achieved
  cadence without HTTP failures.
- Hikari pending peaks demonstrate queue pressure but do not identify a unique
  pathological SQL query or holder.
- the missing `heimdall_deployments_created_total` series in automated runs is
  recorded as missing evidence rather than reconstructed.
- no 5,000 manual simulator/rollout result is claimed because there is no matched
  M10B manual baseline for a defensible before/after comparison.
- the `loadtest` Tomcat configuration is deliberately opt-in and is not a
  recommended universal production default.

Reproduce the matrix using
[`docs/performance/m10d-local-runbook.md`](performance/m10d-local-runbook.md)
without changing workload parameters to improve apparent results.
