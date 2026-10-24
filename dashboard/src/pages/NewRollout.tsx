import { useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { releases } from "../api/releases";
import { vehicles } from "../api/vehicles";
import { deployments } from "../api/deployments";
import { rollouts } from "../api/rollouts";
import { usePolling } from "../api/usePolling";
import {
  Header,
  Panel,
  Loading,
  Empty,
  ErrorState,
  Meta,
} from "../components/ui";
import { activeDeployment, stagePlan, newest } from "../utils/domain";
export function NewRollout() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const q = usePolling((s) =>
    Promise.all([releases.list(s), vehicles.list(s), deployments.list(s)]),
  );
  const [releaseId, setReleaseId] = useState(params.get("release") ?? "");
  const [stageText, setStageText] = useState("5, 25, 50, 100");
  const [threshold, setThreshold] = useState("5");
  const [rollback, setRollback] = useState(true);
  const [review, setReview] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const available = newest(q.data?.[0] ?? []);
  const selected = available.find(
    (r) => r.id === (releaseId || available[0]?.id),
  );
  const targets = (q.data?.[1] ?? []).filter(
    (v) => v.softwareVersion !== selected?.version,
  );
  const targetIds = new Set(targets.map((v) => v.id));
  const blocked = (q.data?.[2] ?? []).filter(
    (d) => targetIds.has(d.vehicleId) && activeDeployment(d),
  );
  const stages = stagePlan(stageText);
  const valid =
    !!selected &&
    !!stages &&
    targets.length > 0 &&
    !blocked.length &&
    Number.isFinite(Number(threshold)) &&
    threshold !== "" &&
    Number(threshold) >= 0 &&
    Number(threshold) <= 100;
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!valid || busy || !selected || !stages) return;
    if (!review) {
      setReview(true);
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      const r = await rollouts.create({
        releaseId: selected.id,
        stages,
        failureThresholdPercent: Number(threshold),
        automaticRollbackEnabled: rollback,
      });
      navigate(`/rollouts/${r.id}?created=1`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Rollout creation failed.");
      setReview(false);
      q.refresh();
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <Header
        eyebrow="ROLLOUTS / NEW JOURNEY"
        title="Start staged rollout"
        description="Choose a release, stage the fleet, and arm the safety envelope."
      />
      <ErrorState message={q.error} retry={q.refresh} stale={!!q.data} />
      {!q.data ? (
        !q.error && <Loading />
      ) : !available.length ? (
        <Panel label="RELEASE REQUIRED">
          <Empty
            title="Create a release first"
            to="/releases/new"
            action="Create release"
          >
            A software journey needs a registered target artifact.
          </Empty>
        </Panel>
      ) : (
        <form onSubmit={submit}>
          <fieldset disabled={busy}>
            <div className="form-layout">
              <Panel
                label="ROLLOUT CONFIGURATION"
                title="Route through the fleet"
              >
                <label>
                  Release
                  <select
                    value={selected?.id ?? ""}
                    onChange={(e) => {
                      setReleaseId(e.target.value);
                      setReview(false);
                    }}
                  >
                    {available.map((r) => (
                      <option key={r.id} value={r.id}>
                        {r.version}
                      </option>
                    ))}
                  </select>
                </label>
                <Meta label="Target snapshot">
                  <strong>{targets.length} eligible vehicles</strong>
                  <p className="muted small">
                    All registered vehicles with a different software version,
                    including offline vehicles. The backend snapshots targets at
                    launch.
                  </p>
                </Meta>
                {blocked.length > 0 && (
                  <div className="notice warning">
                    {blocked.length} active deployment(s) block this journey.
                    Wait for them to finish.
                  </div>
                )}
                <label>
                  Cumulative stage percentages
                  <input
                    required
                    value={stageText}
                    onChange={(e) => {
                      setStageText(e.target.value);
                      setReview(false);
                    }}
                    aria-describedby="stage-help"
                  />
                </label>
                <p id="stage-help" className="muted small">
                  Increasing cumulative targets, ending at 100. Each stage
                  deploys only to the newly included cohort.
                </p>
                {!stages && (
                  <p className="notice warning">
                    Use increasing whole percentages, for example 5, 25, 50,
                    100.
                  </p>
                )}
                <label>
                  Failure threshold (%)
                  <input
                    type="number"
                    min="0"
                    max="100"
                    step="0.01"
                    required
                    value={threshold}
                    onChange={(e) => {
                      setThreshold(e.target.value);
                      setReview(false);
                    }}
                  />
                </label>
                <p className="muted small">
                  Pauses after the current cohort finishes if its failure rate
                  exceeds this threshold. A failed health gate cannot resume.
                </p>
                <label className="checkbox">
                  <input
                    type="checkbox"
                    checked={rollback}
                    onChange={(e) => {
                      setRollback(e.target.checked);
                      setReview(false);
                    }}
                  />
                  Automatic rollback
                </label>
                <p className="muted small">
                  When a health gate fails, create recovery deployments for
                  successful installs from that failed stage. Previous releases
                  must be available.
                </p>
                <ErrorState message={error} retry={() => setError(undefined)} />
                <Link to="/rollouts">Back to software journeys</Link>
              </Panel>
              <Panel label={review ? "REVIEW JOURNEY" : "ROLLOUT SUMMARY"} dark>
                <strong className="hero-number">{selected?.version}</strong>
                <p className="muted">to {targets.length} vehicles</p>
                <hr />
                <Meta label="Stages">{stages?.length ?? "Invalid plan"}</Meta>
                <Meta label="Failure threshold">{threshold}%</Meta>
                <Meta label="Automatic rollback">
                  {rollback ? "Enabled" : "Disabled"}
                </Meta>
                <hr />
                <p className="eyebrow">JOURNEY PREVIEW</p>
                <ol className="plan-preview">
                  {stages?.map((p, i) => {
                    const cumulative =
                      i === stages.length - 1
                        ? targets.length
                        : Math.ceil((targets.length * p) / 100);
                    const prev = i
                      ? Math.ceil((targets.length * stages[i - 1]) / 100)
                      : 0;
                    return (
                      <li key={p}>
                        <strong>{p}%</strong>
                        <span>
                          {cumulative} cumulative · +{cumulative - prev}
                        </span>
                      </li>
                    );
                  })}
                </ol>
                {review && (
                  <p role="status" className="notice">
                    Review this plan. Starting creates deployments immediately
                    for the first stage. Fleet membership can change before
                    launch.
                  </p>
                )}
                <button
                  type="submit"
                  className="accent full"
                  disabled={!valid || busy}
                >
                  {busy
                    ? "Starting…"
                    : review
                      ? "Start rollout"
                      : "Review plan"}
                </button>
                {review && (
                  <button
                    type="button"
                    className="light full"
                    onClick={() => setReview(false)}
                  >
                    Edit plan
                  </button>
                )}
              </Panel>
            </div>
          </fieldset>
        </form>
      )}
    </>
  );
}
