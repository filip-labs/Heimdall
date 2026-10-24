# Dashboard demo and live validation

The fixture uses the real backend and Go simulator. It creates 100 vehicles,
registers four backend-signed releases, and installs 2.0.0 on 35 vehicles through
actual signed downloads. VIN `7FC00000000000003` deterministically fails installation.
No records are hardcoded into the frontend. No benchmark workload runs.

## Repeatable local fixture

Use a **new empty database** for each run because simulator provisioning credentials
are returned once and intentionally stay only in its process memory. The fixture
refuses nonempty API state and never deletes records. These commands assume the
existing Compose Postgres is running on localhost:5433. Choose an unused database
name if `heimdall_m13_demo` already exists.

```bash
# Repository root, Java 21+, Go version from simulator/go.mod, Python 3.
docker exec heimdall-postgres createdb -U heimdall heimdall_m13_demo
bash scripts/dev/generate-compose-env.sh .env.m13
set -a
source .env.m13
set +a
./mvnw -DskipTests package
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/heimdall_m13_demo \
  HEIMDALL_KAFKA_ENABLED=false SERVER_PORT=8081 \
  HEIMDALL_ROLLOUT_EVALUATION_INTERVAL=3s \
  java -jar target/heimdall-0.0.1-SNAPSHOT.jar
```

In a second terminal, export only the matching public OTA key / key ID from the
local `.env.m13` (never distribute the private key or vehicle token secret):

```bash
# Local development shell only. demo.py removes backend secrets from child env.
set -a
source .env.m13
set +a
(cd simulator && go build -o /tmp/heimdall-m13-simulator .)
python3 scripts/m13/demo.py --simulator /tmp/heimdall-m13-simulator --scenario
```

The fixture serves the artifact on loopback port 8099 and keeps the simulator
running until Ctrl-C. Kafka delivery is disabled only for this isolated functional
fixture; database orchestration, authentication, signing, status callbacks, health
gates and rollback are unchanged. The full Java regression suite separately covers
Kafka. No production profiles/security settings change.

In a third terminal (Node 22.14+ LTS):

```bash
cd dashboard
npm ci
API_TARGET=http://localhost:8081 npm run dev
```

Open the printed local URL (normally localhost:5173). Run the read-only verifier:

```bash
python3 scripts/m13/verify.py http://localhost:8081
```

Without `--scenario`, the fixture stops after fleet preparation so Create Release /
Plan Software Journey can be demonstrated through the UI. Choose 2.1.0, cumulative
stages 5 → 25 → 50 → 100, threshold 5%, and automatic rollback. Review and start.
The canary ends with 4 installed / 1 failed, pauses at 20%, leaves future stages
pending, then rolls the four successful installs back to 1.0.0.

To exercise operator controls independently, use a different release with a 100%
threshold and pause promptly. Resume → Pause → Cancel are supported while a stage
is resumable. Already scheduled updates continue when paused/cancelled. Failed
health-gate stages deliberately cannot resume. A manual rollback is available only
for an installed original deployment whose vehicle is still on target and has no
active update, with its previous release registered.

Stop simulator and backend with Ctrl-C when finished. Database records remain for
inspection; use normal local database administration to remove the disposable
fixture when no longer needed. Do not delete the existing application database.

## M13 acceptance evidence (2026-10-24)

- Live browser fleet: 100 online / 0 offline; initial version distribution 65 on
  1.0.0 / 35 on 2.0.0. Search, filters, page navigation and Vehicle Detail tested.
- Create Release submitted through UI on localhost:8080; server-signed release
  returned, success message and release-detail navigation verified.
- Plan/review/start 2.1.0 submitted through UI on isolated localhost:8081.
- Failed stage: exactly 5 original deployments, 4 installed, 1 failed, 20% rate,
  5% threshold, all later stages pending. No Resume control shown.
- Automatic rollback: four installed recovery deployments, original/recovery links.
- Separate operator-paused 2.2.0: Resume, Pause, Cancel all confirmed through UI;
  final CANCELLED state has no enabled controls. Already scheduled installs remain.
- Manual rollback through UI created 2.2.0 → 1.0.0 and opened its real recovery record.
- Actual failed timeline: PENDING → DOWNLOADING → DOWNLOADED → INSTALLING → FAILED,
  ordered timestamps and `simulated installation failure` reason.
- Initial acceptance live verifier: 100 online, software mix 62 / 35 / 3 on 1.0.0 / 2.0.0 / 2.2.0
  after control and manual rollback checks; four automatic recoveries installed.
- A fresh replay using `--scenario` also passed the verifier: 100 online, 65 / 35
  on 1.0.0 / 2.0.0 after automatic recovery. Previous acceptance database retained.
- Loading observed during real requests; empty fleet/releases/rollouts observed
  against empty local API state. Network failure and Retry checked separately.

## Figma comparison and screenshots

[Accepted Figma design](https://www.figma.com/design/NbM5EgN3zF3zekbRZJ4qzZ)
was read through design context and screenshots for all nine implemented frames.
The implementation preserves the 136px dark rail, off-white workspace, Figtree,
yellow operator actions, software river, staged trail, health envelope and three-pane
update journey. Original Figma SVG header/nav/vehicle assets are stored locally.
Responsive composition replaces absolute coordinates. Data-dependent charts use
actual counts. Unsupported mock telemetry/uptime/health claims and global Activity
navigation are omitted. Signature metadata is never labelled browser-verified.

Screenshots in `docs/images/m13/` are real 100-vehicle backend views at desktop size:

- `fleet.jpg`: vehicle landscape, three-version distribution, compact five-row ledger.
- `vehicle.jpg`: vehicle identity, connectivity and actual deployment history.
- `software.jpg`: signed artifact catalog and fleet adoption.
- `rollouts.jpg`: software journey trail, failed-stage health and rollout ledger.
- `rollout-failure.jpg`: paused canary, cumulative/incremental cohorts and incidents.
- `deployment-failure.jpg`: source/target journey and real five-event failure timeline.
- `rollback.jpg`: recovery relationship and real rollback deployment.

## Compose decision / M14 boundary

M13 keeps Vite development separate from the existing Compose stack. A production
static server plus API reverse proxy (including deep-link fallback) should be chosen
alongside M14's final demo entry point. Vite's build output is ready in dashboard/dist;
`vite preview` is only a local preview, not a production server. This avoids adding
another server/runtime solely for this milestone. No backend CORS relaxation.

Deferred to M14: final Compose dashboard hosting, final architecture/ADR consolidation,
video, clean-clone reproduction, and project-wide documentation/portfolio packaging.
No operator IAM, WebSockets, or system-metrics dashboard added.
