import { Link, useParams } from "react-router-dom";
import { vehicles } from "../api/vehicles";
import { deployments } from "../api/deployments";
import { usePolling } from "../api/usePolling";
import {
  Header,
  Panel,
  Status,
  Timestamp,
  Loading,
  Empty,
  ErrorState,
  Meta,
  Freshness,
} from "../components/ui";
import { newest, activeDeployment } from "../utils/domain";
export function VehicleDetail() {
  const { id = "" } = useParams();
  const q = usePolling((s) =>
    Promise.all([vehicles.get(id, s), deployments.list(s)]),
  );
  if (!q.data)
    return (
      <>
        <Header
          eyebrow="FLEET / VEHICLE"
          title="Vehicle detail"
          description="Vehicle identity and update history."
        />
        <ErrorState message={q.error} retry={q.refresh} />
        <Loading />
      </>
    );
  const [v, all] = q.data;
  const history = newest(all.filter((d) => d.vehicleId === v.id));
  const active = history.some(activeDeployment);
  return (
    <>
      <Header
        eyebrow="FLEET / VEHICLE"
        title={v.vin}
        description="Vehicle identity, software state, and real deployment history."
        actions={<Status value={v.connectivityStatus} />}
      />
      <ErrorState message={q.error} retry={q.refresh} stale />
      <div className="three-pane">
        <Panel label="VEHICLE" dark>
          <img
            className="identity-icon"
            src="/assets/vehicle-large.svg"
            width="62"
            height="62"
            alt=""
          />
          <h2>{v.vin}</h2>
          <Meta label="Vehicle ID">
            <code>{v.id}</code>
          </Meta>
          <hr />
          <Meta label="Current software">
            <strong className="hero-number">{v.softwareVersion}</strong>
          </Meta>
          <Meta label="Connectivity">
            <Status value={v.connectivityStatus} />
          </Meta>
          <Meta label="Last seen">
            <Timestamp value={v.lastSeenAt} />
          </Meta>
          <Meta label="Registered">
            <Timestamp value={v.createdAt} />
          </Meta>
          <Link className="button light" to="/fleet">
            Back to fleet
          </Link>
        </Panel>
        <Panel label="SOFTWARE & OTA" title="Update context">
          <Meta label="Current version">
            <strong className="large">{v.softwareVersion}</strong>
          </Meta>
          <hr />
          <p className="eyebrow">DEPLOYMENT HISTORY</p>
          {!history.length ? (
            <Empty title="No deployment history">
              This vehicle has not received a deployment yet.
            </Empty>
          ) : (
            history.map((d) => (
              <div className="history-row" key={d.id}>
                <Link to={`/deployments/${d.id}`}>
                  {d.sourceSoftwareVersion} → {d.targetSoftwareVersion}
                </Link>
                <Status value={d.status} />
                <Timestamp value={d.createdAt} />
                {d.rollbackOfDeploymentId && (
                  <span className="small">Rollback deployment</span>
                )}
              </div>
            ))
          )}
          <div className="notice">
            {active
              ? "An update is currently in progress."
              : "No active deployment."}
          </div>
        </Panel>
        <Panel label="SIGNAL" title="Connectivity">
          <Status value={v.connectivityStatus} />
          <Meta label="Last heartbeat">
            <Timestamp value={v.lastSeenAt} />
          </Meta>
          <Meta label="Record updated">
            <Timestamp value={v.updatedAt} />
          </Meta>
          <hr />
          <p className="muted">
            Connectivity reflects the backend heartbeat timeout. Update
            execution history is recorded in each deployment timeline.
          </p>
        </Panel>
      </div>
      <Freshness value={q.updatedAt} />
    </>
  );
}
