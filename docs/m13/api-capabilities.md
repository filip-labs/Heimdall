# M13 API capability audit

Audited controllers, DTOs, rollout planner/evaluator/control rules, rollback service
and connectivity behavior before implementing the dashboard. Routes use `/api/v1`.

| Screen | Required DTO / data | Existing endpoint | Missing data / decision |
| --- | --- | --- | --- |
| Fleet | VehicleResponse[] | GET /vehicles | Derive counts and distribution over all vehicles, including offline. |
| Vehicle | VehicleResponse; DeploymentResponse[] | GET /vehicles/{id}; GET /deployments | Filter vehicleId. No uptime or fictional telemetry. |
| Software / release | SoftwareReleaseResponse; vehicles | GET /releases; GET /releases/{id}; GET /vehicles | Adoption matches softwareVersion. Backend-signed does not mean browser-verified. |
| Create release | CreateSoftwareReleaseRequest | POST /releases | version, URL, SHA-256, optional description. Backend owns signing. |
| Rollouts | RolloutResponse[]; RolloutStageResponse[] | GET /rollouts; GET /rollouts/{id}/stages | One stage query per rollout per cycle. |
| Plan journey | CreateRolloutRequest; releases / fleet / deployments | POST /rollouts | Cumulative stages ending at 100; threshold; automatic rollback. Target snapshot includes offline vehicles with a different version. Active deployment conflicts enforced by backend. |
| Rollout detail | RolloutResponse; stages; deployments | GET /rollouts/{id}; GET /rollouts/{id}/stages; GET /deployments | Add nullable rolloutId / rolloutStageId to DeploymentResponse: release/time filtering cannot safely identify membership for repeated rollouts. No new endpoint. |
| Controls | Rollout / current stage state | POST /rollouts/{id}/pause, /resume, /cancel | RUNNING pause/cancel. PAUSED cancel/resume, except FAILED current stage cannot resume. Refresh after actions/conflicts. |
| Deployment | DeploymentResponse; DeploymentEventResponse[]; vehicle | GET /deployments/{id}; GET /deployments/{id}/events; GET /vehicles/{id}; GET /deployments | Chronological real events; rollback links by rollbackOfDeploymentId. |
| Rollback | DeploymentResponse | POST /deployments/{id}/rollback | Installed, non-rollback source; previous release available; vehicle still at target; no active deployment. Existing rollback returned idempotently. |

## Metric semantics

Fleet total = all vehicles; online/offline use backend connectivityStatus. Distribution
groups exact softwareVersion including offline. Installed/failed/deployed counts sum
backend incremental stage counters, never cumulative targets. Overall failure rate =
failed/deployed × 100. Current stage rate uses only its own failed/deployment counters,
matching RolloutEvaluationWorker (four decimal places, half-up). The health gate runs only after every deployment in
that stage is terminal and fails strictly above the threshold; earlier rates are provisional.
Stage targetVehicleCount is cumulative; incremental cohort is the difference from the
previous target. Installation progress is installed/totalVehicles. A completed rollout
can have tolerated failures and fewer installed vehicles than its fleet target.

## Networking and scale

Relative API URLs use Vite's /api proxy to localhost:8080. No CORS/security changes.
Trusted control-plane scope, no operator IAM. No credentials/secrets in browser storage.
Poll every 8 seconds after completion; abort on unmount, skip hidden-tab cycles, retain
last good data with stale/error warning. Fleet requests do not scale with vehicle count.
Rollout ledger uses 1+N requests per cycle (one stage request per rollout); revisit for
large rollout archives. No WebSockets, no system metrics duplicated from Grafana.
