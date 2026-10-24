import { Link } from "react-router-dom";
import { rollouts } from "../api/rollouts";
import { usePolling } from "../api/usePolling";
import {
  Header,
  Panel,
  Loading,
  Empty,
  ErrorState,
  Status,
  Freshness,
} from "../components/ui";
import { StageTrail } from "../components/StageTrail";
import { HealthEnvelope } from "../components/HealthEnvelope";
import { newest, stageMetrics } from "../utils/domain";
import { percent } from "../utils/format";
export function Rollouts() {
  const q = usePolling(async (s) => {
    const items = newest(await rollouts.list(s));
    return Promise.all(
      items.map(async (r) => ({
        rollout: r,
        stages: await rollouts.stages(r.id, s),
      })),
    );
  });
  const items = q.data;
  const featured =
    items?.find(
      (x) => x.rollout.status === "RUNNING" || x.rollout.status === "PAUSED",
    ) ?? items?.[0];
  const metrics = stageMetrics(featured?.stages ?? []);
  const current = featured?.stages.find(
    (s) => s.stageIndex === featured.rollout.currentStageIndex,
  );
  return (
    <>
      <Header
        eyebrow="ROLLOUTS"
        title="Software journeys"
        description="Track staged OTA as a route through the fleet."
        actions={
          <Link className="button accent" to="/rollouts/new">
            Plan software journey
          </Link>
        }
      />
      <ErrorState message={q.error} retry={q.refresh} stale={!!items} />
      {!items ? (
        !q.error && <Loading />
      ) : !featured ? (
        <Panel label="JOURNEYS">
          <Empty
            title="No rollouts"
            to="/rollouts/new"
            action="Plan software journey"
          >
            Choose a release and configure a staged route through the fleet.
          </Empty>
        </Panel>
      ) : (
        <>
          <div className="two-pane">
            <Panel
              label="FEATURED JOURNEY"
              title={`Release ${featured.rollout.targetSoftwareVersion}`}
            >
              <div className="split">
                <p className="muted">
                  Targeting {featured.rollout.totalVehicles} vehicles
                </p>
                <Status value={featured.rollout.status} />
              </div>
              <StageTrail stages={featured.stages} horizontal />
              <div className="metric-row">
                <div>
                  <strong>{metrics.installed}</strong>
                  <span>installed</span>
                </div>
                <div>
                  <strong className="danger-text">{metrics.failed}</strong>
                  <span>failed</span>
                </div>
                <div>
                  <strong>{current?.targetVehicleCount ?? 0}</strong>
                  <span>cumulative stage target</span>
                </div>
                <Link
                  className="button"
                  to={`/rollouts/${featured.rollout.id}`}
                >
                  Open rollout ›
                </Link>
              </div>
            </Panel>
            <Panel label="HEALTH ENVELOPE" dark>
              <HealthEnvelope rollout={featured.rollout} stage={current} />
            </Panel>
          </div>
          <Panel label="JOURNEY LEDGER" title="Recent rollouts">
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Release / rollout</th>
                    <th>Status</th>
                    <th>Current stage</th>
                    <th>Installed / fleet</th>
                    <th>Failed</th>
                    <th>Overall failure rate</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map(({ rollout: r, stages }) => {
                    const m = stageMetrics(stages);
                    const stage = stages.find(
                      (s) => s.stageIndex === r.currentStageIndex,
                    );
                    return (
                      <tr key={r.id}>
                        <td>
                          <Link to={`/rollouts/${r.id}`}>
                            Release {r.targetSoftwareVersion}
                          </Link>
                          <small className="muted block">{r.id}</small>
                        </td>
                        <td>
                          <Status value={r.status} />
                        </td>
                        <td>
                          {r.currentStageIndex + 1} / {stages.length} ·{" "}
                          {stage?.targetPercentage ?? 0}% target
                        </td>
                        <td>
                          {m.installed} / {r.totalVehicles}
                          <progress
                            aria-label={`Installed progress for ${r.id}`}
                            value={m.installed}
                            max={Math.max(1, r.totalVehicles)}
                          />
                        </td>
                        <td>{m.failed}</td>
                        <td>{percent(m.failureRate)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </Panel>
        </>
      )}
      <Freshness value={q.updatedAt} />
    </>
  );
}
