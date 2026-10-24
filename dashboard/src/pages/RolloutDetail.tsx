import { useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { rollouts } from "../api/rollouts";
import type { RolloutAction } from "../api/rollouts";
import { deployments } from "../api/deployments";
import { usePolling } from "../api/usePolling";
import {
  Header,
  Panel,
  Loading,
  Empty,
  ErrorState,
  Status,
  Meta,
  Freshness,
} from "../components/ui";
import { StageTrail } from "../components/StageTrail";
import { HealthEnvelope } from "../components/HealthEnvelope";
import { ConfirmAction } from "../components/ConfirmAction";
import { stageMetrics, controls } from "../utils/domain";
import { percent } from "../utils/format";
export function RolloutDetail() {
  const { id = "" } = useParams();
  const [params] = useSearchParams();
  const q = usePolling((s) =>
    Promise.all([
      rollouts.get(id, s),
      rollouts.stages(id, s),
      deployments.list(s),
    ]),
  );
  const [action, setAction] = useState<RolloutAction>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  async function apply() {
    if (!action || busy) return;
    setBusy(true);
    setError(undefined);
    try {
      await rollouts.control(id, action);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Control action failed.");
    } finally {
      setAction(undefined);
      setBusy(false);
      q.refresh();
    }
  }
  if (!q.data)
    return (
      <>
        <Header
          eyebrow="ROLLOUTS / JOURNEY"
          title="Rollout detail"
          description="Stage progression and operational controls."
        />
        <ErrorState message={q.error} retry={q.refresh} />
        {!q.error && <Loading />}
      </>
    );
  const [r, stages, all] = q.data;
  const current = stages.find((s) => s.stageIndex === r.currentStageIndex);
  const m = stageMetrics(stages);
  const allowed = controls(r.status, current);
  const related = all.filter((d) => d.rolloutId === r.id);
  return (
    <>
      <Header
        eyebrow="ROLLOUTS / SOFTWARE JOURNEY"
        title={`Release ${r.targetSoftwareVersion}`}
        description={`Stage ${r.currentStageIndex + 1} of ${stages.length} · ${r.totalVehicles} vehicles · automatic rollback ${r.automaticRollbackEnabled ? "enabled" : "disabled"}`}
        actions={
          <>
            <Status value={r.status} />
            {allowed.pause && (
              <button
                className="light"
                disabled={busy}
                onClick={() => setAction("pause")}
              >
                Pause
              </button>
            )}
            {allowed.resume && (
              <button
                className="accent"
                disabled={busy}
                onClick={() => setAction("resume")}
              >
                Resume
              </button>
            )}
            {allowed.cancel && (
              <button disabled={busy} onClick={() => setAction("cancel")}>
                Cancel rollout
              </button>
            )}
          </>
        }
      />
      {params.has("created") && (
        <div className="notice success" role="status">
          Rollout started. The first cohort is now scheduled.
        </div>
      )}
      <ErrorState
        message={q.error ?? error}
        retry={() => {
          setError(undefined);
          q.refresh();
        }}
        stale={!!q.error}
      />
      {current?.status === "FAILED" && (
        <div className="notice danger">
          <strong>
            Health gate failed at stage {current.stageIndex + 1}. The rollout is{" "}
            {r.status === "PAUSED"
              ? "paused and cannot be resumed"
              : r.status.toLowerCase()}
            . Inspect failures and recovery below.
          </strong>
        </div>
      )}
      <div className="three-pane">
        <Panel label="ROLLOUT" dark className="rollout-summary">
          <strong className="hero-number">
            {current?.targetPercentage ?? 0}%
          </strong>
          <p className="muted small">cumulative stage target</p>
          <hr />
          <div className="metric-row">
            <div>
              <strong>{m.installed}</strong>
              <span>installed</span>
            </div>
            <div>
              <strong className="failure-number">{m.failed}</strong>
              <span>failed</span>
            </div>
          </div>
          <div className="summary-pair">
            <Meta label="Fleet target">{r.totalVehicles} vehicles</Meta>
            <Meta label="Overall failure rate">{percent(m.failureRate)}</Meta>
          </div>
          <div className="accent-rule" />
          <Meta label="Target release">
            <Link to={`/releases/${r.releaseId}`}>
              {r.targetSoftwareVersion}
            </Link>
          </Meta>
          <details className="small">
            <summary>Rollout identifier</summary>
            <code>{r.id}</code>
          </details>
          <p className="muted small">
            Installation progress:{" "}
            {percent(
              r.totalVehicles ? (m.installed / r.totalVehicles) * 100 : 0,
            )}{" "}
            of the fleet target. Counts retain successful installs that later
            rolled back.
          </p>
          <Link className="button light" to="/rollouts">
            Back to rollouts
          </Link>
        </Panel>
        <Panel label="STAGE TRAIL" title="A staged path through the fleet">
          <StageTrail stages={stages} />
          <div className="notice">
            Current cohort: {current?.installedCount ?? 0} installed ·{" "}
            {current?.failedCount ?? 0} failed ·{" "}
            {Math.max(
              0,
              (current?.deploymentCount ?? 0) -
                (current?.installedCount ?? 0) -
                (current?.failedCount ?? 0),
            )}{" "}
            nonterminal
          </div>
        </Panel>
        <Panel label="STAGE HEALTH">
          <HealthEnvelope rollout={r} stage={current} />
        </Panel>
      </div>
      <Panel label="DEPLOYMENT STRIP" title="Vehicles on this journey">
        {!related.length ? (
          <Empty title="No deployments recorded">
            Refresh after the first cohort has been scheduled.
          </Empty>
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Vehicle</th>
                  <th>Transition</th>
                  <th>Stage</th>
                  <th>Status</th>
                  <th>Failure / inspection</th>
                </tr>
              </thead>
              <tbody>
                {related
                  .sort(
                    (a, b) =>
                      (a.status === "FAILED" ? -1 : 0) -
                        (b.status === "FAILED" ? -1 : 0) ||
                      a.vin.localeCompare(b.vin),
                  )
                  .map((d) => (
                    <tr key={d.id}>
                      <td>
                        <Link to={`/vehicles/${d.vehicleId}`}>{d.vin}</Link>
                      </td>
                      <td>
                        {d.sourceSoftwareVersion} → {d.targetSoftwareVersion}
                      </td>
                      <td>
                        {(stages.find((s) => s.id === d.rolloutStageId)
                          ?.stageIndex ?? -1) + 1}
                      </td>
                      <td>
                        <Status value={d.status} />
                      </td>
                      <td>
                        {d.failureReason && (
                          <p className="danger-text">{d.failureReason}</p>
                        )}
                        <Link to={`/deployments/${d.id}`}>
                          Open deployment ›
                        </Link>
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
      <Freshness value={q.updatedAt} />
      {action && (
        <ConfirmAction
          title={
            action === "cancel"
              ? "Cancel rollout"
              : action === "pause"
                ? "Pause rollout"
                : "Resume rollout"
          }
          description={
            action === "cancel"
              ? "Stop scheduling future stages. In-flight deployments continue and history is retained."
              : action === "pause"
                ? "Pause progression to the next stage. Already scheduled vehicle deployments continue."
                : "Resume stage evaluation and progression. The backend rechecks whether this stage can resume."
          }
          busy={busy}
          onClose={() => setAction(undefined)}
          onConfirm={() => void apply()}
        />
      )}
    </>
  );
}
