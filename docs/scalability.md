# Heimdall scalability report — M10D results pending

This is a preparation skeleton, not a completed capacity report. M10D has not
been executed. See [the runbook](performance/m10d-local-runbook.md) for exact
startup/configuration, scenario matrix, IDs, reset policy and evidence requirements.

## Environment

Pending M10D: tested SHA, active profiles, exact commands/environment/JVM flags,
OS/CPU/RAM, JDK/Maven/Go/k6 versions, Docker resource limits and service versions.
Record deviations from the local Mac M10B/M10C environment.

## Methodology

Repeat original registration/heartbeat/polling/mixed, simulator and healthy staged
rollout baselines at 100/1000/5000 where practical. Preserve workload shape,
cadence, phase durations, database state and measured repetitions. Separate warmup,
setup and steady; report generator pressure and missing coverage. Do not replace
original mixed with diagnostic retries. Do not present setup/global p99 as steady p99.

## M10B baseline

Immutable executable reference: 6699f04d6ea7d933cb4c161fd62b4e2745cd8caf.
Use the [M10B runbook](performance/m10b-local-runbook.md) and retained per-run
metadata for actual measured SHAs/configurations. Pending final report: inventory
matched raw/summary evidence for each scenario and classify unmatched comparisons.

## Completed M10C investigation

The 8192 Tomcat admission limit was a confirmed material bottleneck. Controlled
12000 capacity admitted 10000/10000 VUs instead of 8191, eliminated 1809 exhausted
VUs and 5427 resolve failures, and reduced HTTP failures from ~0.95% to0%.
5103 and 5104 both achieved full admission. 5104 global/steady p99 was
3226.55928/3240.29272ms, zero HTTP failures and k6 exit 0. These are **historical
M10C diagnostic findings, not M10D baseline-equivalent results**.

Recurring Hikari transaction-entry waits remain; the overall hypothesis is
SUPPORTED/MEDIUM, without a unique pathological holder/query or proven next tuning
variable. M10C tuning stops here. No 5105 experiment. See the
[final M10C conclusion](../scripts/m10c/README.md#final-m10c-conclusion-and-transition-to-m10d)
and linked evidence reviews for details and run-to-run variability.

## Confirmed capacity treatment

Opt-in `application-loadtest.yml`: Tomcat maxConnections 12000, MBeans enabled.
Normal application.yml stays unchanged. Worker default 200, Hikari default 10;
no DB/Kafka/JVM/OS or business-logic tuning. This environment-specific capacity
budget is not a universal production default. Trade-off: more admitted sockets
consume resources and expose downstream queues; one passing run is insufficient
to promise stable production latency.

## M10D results — PENDING, NOT EXECUTED

| Profile | Original k6 scenarios | Simulator / healthy rollout | Results/evidence |
|---|---|---|---|
| 100 | registration, heartbeat, polling, mixed | pending | PENDING |
| 1000 | registration, heartbeat, polling, mixed | pending | PENDING |
| 5000 | same, where practical | pending/practicality review | PENDING |

After execution add per-run SHA/command/profile/configuration/evidence links,
throughput and operation rates, p50/p95/p99, errors, dropped/interrupted iterations,
CPU/memory, Hikari, PG waits, Kafka/outbox and rollout/deployment metrics. Include
measured repetitions, improvements, regressions and noise; never select only the
best run. Record Java/Go/race/tooling/CI results for the exact tested revision.

## Limitations and reproducibility

Local Mac/shared-host results are not production capacity. Sparse metric sampling,
closed-loop workload timing, incomplete resolution, DB/cache state and generator
pressure can affect comparisons. Preserve original evidence outside Git; link raw
and summary locations with hashes. Keep JFR optional and account for its overhead
when comparing diagnostic runs. Follow the M10D runbook without changing workload
parameters to improve apparent results. Final conclusions remain pending M10D.
