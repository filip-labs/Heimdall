import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { deployments } from "../api/deployments";
import { vehicles } from "../api/vehicles";
import { releases } from "../api/releases";
import { usePolling } from "../api/usePolling";
import { activeDeployment } from "../utils/domain";
import { shortId } from "../utils/format";
import {
  Header,
  Panel,
  Loading,
  Empty,
  ErrorState,
  Status,
  Timestamp,
  Meta,
  Freshness,
} from "../components/ui";
import { ConfirmAction } from "../components/ConfirmAction";
export function DeploymentDetail() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const q = usePolling(async (s) => {
    const [d, events, all, catalog] = await Promise.all([
      deployments.get(id, s),
      deployments.events(id, s),
      deployments.list(s),
      releases.list(s),
    ]);
    const v = await vehicles.get(d.vehicleId, s);
    return { d, events, all, catalog, v };
  });
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  async function rollback() {
    if (busy) return;
    setBusy(true);
    setError(undefined);
    try {
      const result = await deployments.rollback(id);
      navigate(`/deployments/${result.id}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Rollback failed.");
    } finally {
      setBusy(false);
      setConfirm(false);
      q.refresh();
    }
  }
  if (!q.data)
    return (
      <>
        <Header
          eyebrow="DEPLOYMENT"
          title="Vehicle update journey"
          description="Inspect deployment transition history."
        />
        <ErrorState message={q.error} retry={q.refresh} />
        <Loading />
      </>
    );
  const { d, events, all, catalog, v } = q.data;
  const recovery = all.find((x) => x.rollbackOfDeploymentId === d.id);
  const canRollback =
    d.status === "INSTALLED" &&
    !d.rollbackOfDeploymentId &&
    !recovery &&
    v.softwareVersion === d.targetSoftwareVersion &&
    !all.some((x) => x.vehicleId === v.id && activeDeployment(x)) &&
    catalog.some((r) => r.version === d.sourceSoftwareVersion);
  const ordered = [...events].sort(
    (a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt),
  );
  return (
    <>
      <Header
        eyebrow="DEPLOYMENT / VEHICLE JOURNEY"
        title="Vehicle update journey"
        description={`${d.vin} · Deployment ${shortId(d.id)}`}
        actions={<Status value={d.status} />}
      />
      <ErrorState
        message={q.error ?? error}
        retry={q.refresh}
        stale={!!q.error}
      />
      <div className="three-pane">
        <Panel label="VEHICLE" dark>
          <img
            className="identity-icon"
            src="/assets/vehicle-dark.svg"
            width="62"
            height="62"
            alt=""
          />
          <h2>
            <Link to={`/vehicles/${d.vehicleId}`}>{d.vin}</Link>
          </h2>
          <hr />
          <p className="eyebrow">SOFTWARE TRANSITION</p>
          <p className="transition">
            {d.sourceSoftwareVersion} <span>→</span> {d.targetSoftwareVersion}
          </p>
          <div className="accent-rule" />
          <Meta label="Failure">
            {d.failureReason || "No failure recorded"}
          </Meta>
          <Meta label="Created">
            <Timestamp value={d.createdAt} />
          </Meta>
          <Meta label="Updated">
            <Timestamp value={d.updatedAt} />
          </Meta>
          <Meta label="Deployment ID">
            <code>{d.id}</code>
          </Meta>
          {d.rolloutId && (
            <Link className="button light" to={`/rollouts/${d.rolloutId}`}>
              Back to rollout
            </Link>
          )}
        </Panel>
        <Panel label="DEPLOYMENT JOURNEY" title="Recorded state transitions">
          {!ordered.length ? (
            <Empty title="No deployment events">
              No event history was returned for this deployment.
            </Empty>
          ) : (
            <ol className="event-timeline">
              {ordered.map((event, i) => (
                <li key={event.id}>
                  <span
                    className={`event-marker ${event.toStatus.toLowerCase()}`}
                    aria-hidden="true"
                  >
                    {event.toStatus === "FAILED" ? "×" : "✓"}
                  </span>
                  <div>
                    <Timestamp value={event.createdAt} />
                    <Status value={event.toStatus} />
                    <p>
                      {event.fromStatus
                        ? `${event.fromStatus} → ${event.toStatus}`
                        : "Deployment created"}
                    </p>
                    {event.failureReason && (
                      <div className="notice danger">{event.failureReason}</div>
                    )}
                  </div>
                  <span className="sr-only">Event {i + 1}</span>
                </li>
              ))}
            </ol>
          )}
          <Meta label="Release">
            <Link to={`/releases/${d.releaseId}`}>
              Release {d.targetSoftwareVersion} ›
            </Link>
          </Meta>
        </Panel>
        <Panel label="RECOVERY" title="Rollback path">
          {d.rollbackOfDeploymentId ? (
            <>
              <p>This deployment restores the previous software version.</p>
              <Meta label="Original deployment">
                <Link to={`/deployments/${d.rollbackOfDeploymentId}`}>
                  {shortId(d.rollbackOfDeploymentId)} ›
                </Link>
              </Meta>
            </>
          ) : recovery ? (
            <>
              <Meta label="Original update">
                {d.sourceSoftwareVersion} → {d.targetSoftwareVersion}
              </Meta>
              <Meta label="Rollback deployment">
                <Link to={`/deployments/${recovery.id}`}>
                  {recovery.sourceSoftwareVersion} →{" "}
                  {recovery.targetSoftwareVersion} ›
                </Link>
              </Meta>
              <Status value={recovery.status} />
            </>
          ) : (
            <>
              <p className="muted">No rollback deployment recorded.</p>
              {canRollback ? (
                <button
                  className="accent full"
                  disabled={busy}
                  onClick={() => setConfirm(true)}
                >
                  Start rollback
                </button>
              ) : (
                <p className="notice">
                  Rollback requires an installed original deployment, its
                  previous release, no active update, and a vehicle still on the
                  installed target.
                </p>
              )}
            </>
          )}
          <hr />
          <Meta label="Current vehicle software">{v.softwareVersion}</Meta>
          <Meta label="Connectivity">
            <Status value={v.connectivityStatus} />
          </Meta>
          <Meta label="Signing key">{d.signingKeyId}</Meta>
          <Meta label="SHA-256">
            <code>{d.checksum}</code>
          </Meta>
        </Panel>
      </div>
      <Freshness value={q.updatedAt} />
      {confirm && (
        <ConfirmAction
          title="Start rollback"
          description={`Create a recovery deployment to restore ${d.sourceSoftwareVersion}. The vehicle executes the normal authenticated update journey.`}
          busy={busy}
          onClose={() => setConfirm(false)}
          onConfirm={() => void rollback()}
        />
      )}
    </>
  );
}
