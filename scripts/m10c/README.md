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

### Interpretation rule

The diagnostic workload prevents resolution retry amplification, but VUs that exhaust their
resolution attempts no longer generate normal heartbeat or polling requests. An apparent
performance improvement is directly comparable to the original 5,000 mixed workload only when
resolution exhaustion is zero or negligible. If significant VUs are exhausted, the run still
demonstrates retry-amplification behavior, but it is not an identical offered-load capacity result.

Before interpreting heartbeat/poll throughput, engineering review must examine
`m10c_vehicle_resolve_attempts`, `m10c_vehicle_resolve_failures`,
`m10c_vehicle_resolve_retries`, and `m10c_vehicle_resolve_exhausted`.

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
./mvnw spring-boot:run 2>&1 | tee /tmp/heimdall-m10c-5101-backend.log
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

Preserve `/tmp/heimdall-m10c-5101-backend.log` together with the generated result directory. The
diagnostic runner intentionally does not own or stop the Spring Boot process.

## Evidence to return

Send the entire new result directory, plus:

- `k6-summary.json`, `k6-console.log`, `k6-exit-code.txt`, and `git-sha.txt`;
- `collector.log` and `collector-status.txt`, including an unexpected early collector exit;
- `/tmp/heimdall-m10c-5101-backend.log`;
- counts for `m10c_vehicle_resolve_attempts`, `failures`, `retries`, and `exhausted` by scenario;
- heartbeat and `deployment_poll` achieved rates, p95/p99, failures, and dropped iterations;
- `runtime-samples.jsonl`, including every Prometheus scrape timeout;
- exposed/missing Tomcat thread and connection series;
- process CPU, JVM live threads, Hikari active/idle/pending/max;
- PostgreSQL activity/wait-state samples and container CPU/memory;
- backend log excerpts containing Tomcat/NIO/socket errors during the diagnostic window.

The next engineering review decides whether retry amplification materially changes the failure and
which backend hypothesis deserves the first controlled experiment. No tuning follows automatically.

## Phase 2: direct local Tomcat JMX (completed run 5102)

Phase 1 run **5101** resolved exactly 8,192 of 10,000 VUs; 1,808 exhausted resolution
(13,616 attempts, 5,424 failures, 3,616 retries). Achieved heartbeat/poll rates were about
269.32/404.48 requests/sec. Hikari reached active=10/10 and pending=22 in one observation;
40/77 HTTP scrapes timed out. The 100 late Tomcat/NIO errors were during ramp-down.
No Tomcat connection count was captured in 5101. At that stage the 8,192 connection-boundary hypothesis was
**SUPPORTED BUT NOT CONFIRMED**. A single Hikari spike does not establish persistent pool saturation.

### Instrumentation and evidence

`collect.py` starts one persistent Java 21 source-file helper, `TomcatJmxProbe.java`, using only
JDK Attach/JMX APIs. It discovers the actual `dev.heimdall.HeimdallApplication` Java process with
macOS/Linux `ps`, excludes Maven launchers, and refuses zero/multiple candidates. The helper
also verifies `sun.java.command` before starting management, protecting against a wrong/reused PID.
This discovery supports the documented `spring-boot:run` main-class launch; packaged `java -jar`
launches are deliberately unsupported rather than guessed.

The helper attaches once, reuses an existing local management connector or starts the JDK local
management agent once, detaches the Attach handle, and retains the JMX connection. No remote JMX
port is configured and no server capacity attribute is written. The local agent remains in the
backend until that JVM exits. Run the collector as the same OS user, with a full Java 21 JDK and
Attach enabled. See the [JDK Attach API](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.attach/com/sun/tools/attach/VirtualMachine.html#startLocalManagementAgent()).

Every sample dynamically queries ThreadPool, Connector, and ProtocolHandler MBeans across domains
and discovers readable attributes with MBeanInfo. ObjectName handles quoting. The selected
ThreadPool endpoint must uniquely match port 8080 (`--jmx-port` can override this in the collector).
Selection uses `localPort`, then `port` if absent, then a protocol-name port suffix only if both
attributes are absent. The exact source, all candidates, raw values, attribute errors and available
attribute names are retained. Two endpoints matching the same port are ambiguous, even if they
use different interfaces: no endpoint is silently picked or aggregated.

Each JSONL sample adds:

| Stable field | Discovered ThreadPool attribute |
|---|---|
| `tomcat_jmx_connection_count` | `connectionCount` |
| `tomcat_jmx_max_connections` | `maxConnections` |
| `tomcat_jmx_keep_alive_count` | `keepAliveCount` |
| `tomcat_jmx_current_threads` | `currentThreadCount` |
| `tomcat_jmx_busy_threads` | `currentThreadsBusy` |
| `tomcat_jmx_max_threads` | `maxThreads` |

`tomcat_jmx` holds success/error, selected PID, PID candidates, selected MBean/connector,
raw helper evidence, unavailable attributes, timestamp, elapsed seconds, helper start count and
timeout settings. Missing/non-numeric/negative sentinel attributes become `null`; raw sentinel
values remain in metadata. Partial availability is reported explicitly; `ok=true` means at least
one metric was read, not that every metric is available. Never treat a missing limit as 8,192.
The endpoint descriptors in the locally resolved Tomcat 11.0.24 JAR expose these six attributes;
a separate idle Tomcat 11.0.24 fixture on an ephemeral loopback port also exposed all six via
real Attach/JMX. No Heimdall, application data or HTTP load was used for that validation. The fixture
reported connectionCount=1, maxConnections=8192, keepAliveCount=0, currentThreadCount=10,
currentThreadsBusy=0 and maxThreads=200. These are fixture observations, not run 5102 evidence.
Tomcat's endpoint counter can include an in-progress accept reservation; do not equate it exactly
with established OS sockets. Future backend availability still must be checked with the registry enabled.

JMX is read **before** Actuator, so an HTTP timeout cannot erase its evidence. The first response
has a 15-second deadline (including source compilation, Attach and connect); subsequent responses
have a 3-second deadline. PID discovery has a 3-second timeout. Timeout/transport/malformed-output
failures terminate and reap the helper (at most two additional seconds for cleanup), record nulls,
and back off 60 seconds before retrying. An absent MBean does not restart a healthy helper.
A retry reuses the target's management agent. SIGTERM/SIGINT close the helper while preserving
collector exit status 143/130 and the runner's existing `collector-status.txt` behavior.
Errors appear both in JSONL and `collector.log`. All existing HTTP, process, Hikari, PostgreSQL and
container evidence remains enabled.

The helper compiles once per lifetime, not per sample; only small JMX reads occur during normal
sampling. The existing loop still sleeps five seconds **after** its observations, so slow HTTP or
Docker calls reduce sample density. JMX has its own timestamp and elapsed time; it is independent
of HTTP transport, but is not an independent fixed-frequency sampler. JVM pauses/OS exhaustion can
also delay JMX. There is no claim of zero observer overhead or proof of a boundary from gaps alone.

### Archived 5102 procedure

**Run 5102 is complete; do not rerun or overwrite its evidence.** The commands below record its
original procedure. For the next experiment use Phase 3 below. Preserve run 5101 evidence and its backend log first. Stop the old
backend and other experiments; prepare the same fresh disposable Compose database state as 5101
using the Phase 1 reset procedure above (it deletes the local Compose volume).

Terminal 1 — backend, Java 21, registry enabled only for this process:

```bash
cd /Users/filip_macbook/heimdall
git switch m10c-phase1-diagnostics
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments=--server.tomcat.mbeanregistry.enabled=true \
  2>&1 | tee /tmp/heimdall-m10c-5102-backend.log
```

Wait for `curl --fail http://localhost:8080/actuator/health` to succeed. No `maxConnections`,
`maxThreads`, Hikari, JVM memory/GC or OS settings are overridden.

Terminal 2 — preflight (JMX only) followed by the future diagnostic workload:

```bash
cd /Users/filip_macbook/heimdall
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
python3 -B scripts/m10c/collect.py --jmx-once && \
PROFILE=5000 RUN_ID=5102 \
RAMP_UP=5m STEADY_DURATION=10m RAMP_DOWN=1m SETUP_TIMEOUT=10m \
HEARTBEAT_INTERVAL=15s POLL_INTERVAL=10s \
DIAGNOSTIC_RESOLVE_MAX_ATTEMPTS=3 DIAGNOSTIC_RESOLVE_BACKOFF=5s \
scripts/m10c/run-mixed-diagnostic.sh
```

Preflight returns nonzero unless all five non-optional metrics are available, and displays actual
PID, MBeans and runtime limits for review. Missing keep-alive is permitted. If preflight fails,
inspect its error and backend startup before running load. A preflight may start the local agent;
the regular collector then reuses it. Do not bypass a failure by assuming defaults.

Return the complete result directory and `/tmp/heimdall-m10c-5102-backend.log`. Align direct
connection/thread observations with resolve attempts/exhaustion and the k6 ramp/steady/down timeline,
especially samples with `prometheus_error`. Check JMX success/coverage, runtime maximum,
selected connector and unavailable attributes before making a saturation claim.

### 5102 decision rules

- **A — Connection boundary:** connections approach/plateau near 8,192, observed runtime
  maxConnections is 8,192, and resolution exhaustion occurs during that plateau:
  **8192 connection-boundary hypothesis => STRONGLY SUPPORTED**. The next separately reviewed
  experiment may change only maxConnections. This implementation does not do that experiment.
- **B — Worker threads:** connections stay materially below 8,192 while busy threads approach
  maxThreads: investigate worker-thread saturation next.
- **C — Pool pressure:** connections stay below the suspected limit while Hikari is persistently
  saturated with pending waiters: database connection-pool pressure becomes the stronger candidate.
- **D — OS/network:** JMX shows no connection/thread boundary while OS/socket evidence dominates:
  investigate macOS/network limits separately. Missing JMX data is inconclusive, not proof of D.

### Bounded correctness checks (no benchmark)

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
bash -n scripts/m10b/run.sh scripts/m10b/scenario.sh scripts/m10c/run-mixed-diagnostic.sh
python3 -m py_compile scripts/m10c/collect.py scripts/m10c/tests/test_collect.py scripts/m10c/tests/test_jmx.py
python3 -B -m unittest discover -s scripts/m10c/tests -v
python3 -B -m unittest discover -s scripts/m10b/tests -v
npm --prefix load-tests test
```

M10C tests compile the helper and a JDK-only idle fixture into a temporary directory, exercise real
local Attach/JMX with quoted MBeans and two ports, verify connection/agent reuse, and clean up their
processes. They never launch Heimdall or generate application traffic/data. The tooling CI job
provides Java 21 and runs these tests without skipping Attach coverage.

## Phase 3: controlled connection-limit experiment (5103, NOT executed)

### 5102 findings

Run 5102 had 10,000 maximum VUs, 8,191 successful vehicle resolves and 1,809 exhausted VUs.
There were 13,618 resolve attempts, 5,427 failures and 3,618 retries: exactly
`1809 × 3 = 5427` failed attempts and `1809 × 2 = 3618` retries. It recorded 220,990
successful heartbeats and 328,965 successful polls, approximately 0.95% global HTTP failures,
global p99 approximately 4.62 seconds, and k6 exit code 0. Exit code 0 does not mean the full
intended fleet established its workload.

The pre-load direct JMX check reported maxConnections=8192, maxThreads=200, connections near
idle=2 and busy threads=0. During ramp-up, Micrometer recorded connections around 8077 with
busy threads around 1, maxThreads=200 and Hikari pending=0 before/around the onset of overload.
Later connection samples reached 8192; k6 reported `dial: i/o timeout` on vehicle resolution.
Busy threads did not approach 200 at the connection boundary. Resolution counts nearly repeat
5101 (8192 resolved/1808 exhausted versus 8191/1809).

Direct JMX **during** 5102 failed with `Use Java 21 for this experiment`; it must not be cited
as successful in-run direct JMX evidence. The preflight and Micrometer evidence nevertheless make
the 8192 connection-boundary hypothesis **STRONGLY SUPPORTED**, not mathematically proven.
The controlled experiment below tests its causal significance.

### Java mismatch and deterministic selection

The old launcher selected `$JAVA_HOME/bin/java` without checking its version, otherwise plain
`java` from PATH. The shell runner did not modify JAVA_HOME or PATH: Python and its helper inherited
the workload terminal's environment, independently of the already-running backend. Source-file
execution compiles and executes inside that selected Java launcher; it does not call a different
PATH `javac`. The helper's guard checked its own runtime, not the target backend runtime.

5102 process evidence shows the backend using the explicit OpenJDK 21 installation, while the
helper guard proves its own runtime was not 21. At investigation time JAVA_HOME was unset and
`/usr/bin/java -version` selected OpenJDK 24.0.1, reproducing the unsafe fallback condition.
The historical helper path/environment was not recorded, so whether JAVA_HOME was missing or
pointing to another JDK in that specific terminal cannot be established retrospectively.

The collector now verifies a full JDK in this order:

1. JAVA_HOME when its runtime and compiler are Java 21.
2. On macOS, `/usr/libexec/java_home -v 21`, with both executables verified.
3. PATH Java only after verifying feature version 21 and its own installation's javac 21.
4. Otherwise report a useful error; never silently use a different Java feature version.

Each version command has a three-second timeout. The selected runtime's absolute `java.home/bin/java`
and sibling `javac` paths, feature version, selection source and rejected candidates are recorded
under `tomcat_jmx.jdk`. The source helper does not invoke javac, but its availability/version proves
that the selected installation is a full JDK. Selection is cached, not repeated every sample.
The helper's own Java 21 guard remains unchanged.

### Mandatory runner preflight

Before starting the background collector or any k6 setup/traffic, the runner invokes
`collect.py --preflight`. It requires Java 21, HTTP health status UP (five-second HTTP timeout),
a unique Heimdall PID, successful direct JMX for the connector on port 8080 and all five
non-optional connection/thread metrics, including maxConnections and maxThreads. Keep-alive may
be unavailable. Existing PID/JMX/cleanup deadlines remain in force; the checks do not retry forever.
Failures print the reason and exit nonzero **without starting k6**.

`preflight.json` and `preflight.log` are retained in the new result directory. The runner passes
the selected JDK home explicitly to the background collector via `--java-home`; a pinned JDK
is revalidated and cannot fall back to another installation. Both preflight and regular sampling
record the selected Java paths/version. After startup, sample failures remain fail-soft and all
HTTP, Tomcat Micrometer, JMX, Hikari, process, PostgreSQL and container evidence is preserved.
The gate verifies current readiness; it cannot guarantee that the backend stays healthy afterward.

### Exact future 5103 procedure

**Do not execute 5103 as part of this preparation task.** Preserve 5101 and 5102 result directories
and backend logs unchanged. Stop the previous backend, and prepare the same fresh disposable
Compose/database state used for 5102 before this future experiment. The only capacity variable
changed from 5102 is **Tomcat maxConnections: 8192 → 12000**. No maxThreads, Hikari, DB, Kafka,
heap/GC or macOS settings change. MBean registry remains enabled as it was in 5102.

Property verification: the project's resolved Spring Boot 4.1.1 `spring-boot-tomcat` JAR configuration
metadata declares `server.tomcat.max-connections` (Integer, default 8192) and
`server.tomcat.mbeanregistry.enabled` (Boolean, default false). The matching Maven plugin descriptor
binds `spring-boot.run.arguments` to its command-line argument string; quoting the entire two-argument
value below passes both application flags. Neither property is added to `application.yml`.

Terminal 1:

```bash
cd /Users/filip_macbook/heimdall
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--server.tomcat.mbeanregistry.enabled=true --server.tomcat.max-connections=12000" \
  2>&1 | tee /tmp/heimdall-m10c-5103-backend.log
```

Wait for backend readiness. Before starting the future workload, use
`python3 -B scripts/m10c/collect.py --preflight` to inspect actual maxConnections=12000 and
maxThreads=200. Stop and investigate if either differs. This manual check does not replace the
runner's automatic gate. The generic runner checks readable limits, not a hardcoded capacity value.

Terminal 2 (same workload parameters as 5102; automatic preflight is included):

```bash
cd /Users/filip_macbook/heimdall
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
PROFILE=5000 RUN_ID=5103 \
RAMP_UP=5m STEADY_DURATION=10m RAMP_DOWN=1m SETUP_TIMEOUT=10m \
HEARTBEAT_INTERVAL=15s POLL_INTERVAL=10s \
DIAGNOSTIC_RESOLVE_MAX_ATTEMPTS=3 \
DIAGNOSTIC_RESOLVE_BACKOFF=5s \
scripts/m10c/run-mixed-diagnostic.sh
```

### 5103 comparison and interpretation

Compare against 5102 with identical workload parameters and matching time windows:

- Preflight maxConnections=12000, maxThreads=200; successful direct JMX coverage during load.
- Connection counts able to exceed 8192, and their relationship to resolution failures over time.
- Resolved VUs increasing substantially toward 10000; exhausted VUs falling substantially from 1809.
- Dial/connect timeouts falling substantially; retain attempts, failures and retries by scenario.
- Heartbeat/poll cadence approaching the intended full-fleet steady rates (about 333.33/500 requests/sec),
  with HTTP failure rates, p95/p99 and dropped iterations retained. Whole-run totals include ramps
  and cannot substitute for steady-window comparisons.

Perfect zero failures is not required. If connections exceed 8192, resolved VUs rise close to 10000
and exhausted VUs collapse while all other workload settings are identical, classify:
**Tomcat maxConnections was a confirmed material bottleneck**. This does not establish that it
is the only remaining bottleneck.

If more connections expose busyThreads ≈ maxThreads, investigate worker threads next. If Hikari
active remains 10 and pending grows persistently, investigate the DB pool next. If connections
exceed 8192 but resolution exhaustion remains high without a thread/Hikari boundary, investigate
OS/socket behavior next. Do not tune any of these in this task.

Tests additionally cover Java selection/fallback on mocked macOS/Linux environments, wrong/missing
JDKs, health/JMX gate failures and shell-runner ordering with fake executables. No runner test invokes
real k6. Existing real JDK Attach fixture tests and collector failure tests remain enabled in CI.
