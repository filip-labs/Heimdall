import { useState } from "react";
import { Link } from "react-router-dom";
import { vehicles } from "../api/vehicles";
import { usePolling } from "../api/usePolling";
import {
  Header,
  Panel,
  Status,
  Timestamp,
  Loading,
  Empty,
  ErrorState,
  Freshness,
} from "../components/ui";
import { SoftwareDistribution } from "../components/SoftwareDistribution";
import { fleetCounts } from "../utils/domain";
import { number } from "../utils/format";
const PAGE_SIZE = 5;

export function Fleet() {
  const query = usePolling(vehicles.list);
  const [search, setSearch] = useState("");
  const [state, setState] = useState("ALL");
  const [page, setPage] = useState(0);
  const list = query.data;
  const counts = fleetCounts(list ?? []);
  const filtered = (list ?? [])
    .filter(
      (v) =>
        v.vin.toLowerCase().includes(search.toLowerCase()) &&
        (state === "ALL" || v.connectivityStatus === state),
    )
    .sort((a, b) => a.vin.localeCompare(b.vin));
  const pages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const current = Math.min(page, pages - 1);
  return (
    <>
      <Header
        eyebrow="FLEET"
        title="Vehicle landscape"
        description="A glanceable view of connectivity and software state."
      />
      <ErrorState message={query.error} retry={query.refresh} stale={!!list} />
      {!list ? (
        !query.error && <Loading />
      ) : !list.length ? (
        <Panel label="FLEET">
          <Empty title="No vehicles registered">
            Start the vehicle simulator against this control plane to populate
            the fleet.
          </Empty>
        </Panel>
      ) : (
        <>
          <div className="fleet-grid">
            <Panel label="FLEET PULSE">
              <div className="pulse-head">
                <div>
                  <strong className="hero-number">
                    {number(counts.online)}
                  </strong>
                  <p className="muted">online</p>
                </div>
                <div>
                  <strong className="success-text big">
                    {((counts.online / counts.total) * 100).toFixed(1)}%
                  </strong>
                  <p className="muted small">connected</p>
                </div>
              </div>
              <div className="pulse-bottom">
                <div
                  className="donut"
                  role="img"
                  aria-label={`${counts.online} online, ${counts.offline} offline`}
                  style={{
                    background: `conic-gradient(var(--color-success) ${(counts.online / counts.total) * 100}%, var(--color-border) 0)`,
                  }}
                />
                <div>
                  <strong className="danger-text big">{counts.offline}</strong>
                  <p className="muted small">offline</p>
                  <strong className="big">{number(counts.total)}</strong>
                  <p className="muted small">total</p>
                </div>
              </div>
            </Panel>
            <Panel label="SOFTWARE LANDSCAPE">
              <SoftwareDistribution vehicles={list} />
            </Panel>
            <Panel label="ATTENTION" dark>
              <strong className="hero-number">{counts.offline}</strong>
              <p className="muted">vehicles offline</p>
              <div className="accent-rule" />
              <p className="muted small">Latest offline signals</p>
              {list
                .filter((v) => v.connectivityStatus === "OFFLINE")
                .sort((a, b) =>
                  (b.lastSeenAt ?? "").localeCompare(a.lastSeenAt ?? ""),
                )
                .slice(0, 3)
                .map((v) => (
                  <div className="signal-row" key={v.id}>
                    <Link to={`/vehicles/${v.id}`}>{v.vin}</Link>
                    <Timestamp value={v.lastSeenAt} />
                  </div>
                ))}
              {!counts.offline && <p>All registered vehicles are online.</p>}
            </Panel>
          </div>
          <Panel label="VEHICLES" title="Fleet details" className="fleet-table">
            <div className="table-tools">
              <label className="search">
                <img src="/assets/search.svg" width="20" height="20" alt="" />
                <input
                  aria-label="Search VIN"
                  placeholder="Search VIN"
                  value={search}
                  onChange={(e) => {
                    setSearch(e.target.value);
                    setPage(0);
                  }}
                />
              </label>
              <select
                aria-label="Connectivity filter"
                value={state}
                onChange={(e) => {
                  setState(e.target.value);
                  setPage(0);
                }}
              >
                <option value="ALL">All states</option>
                <option>ONLINE</option>
                <option>OFFLINE</option>
              </select>
            </div>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Vehicle / VIN</th>
                    <th>Software</th>
                    <th>Connection</th>
                    <th>Last signal</th>
                    <th>
                      <span className="sr-only">Inspect</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {filtered
                    .slice(current * PAGE_SIZE, current * PAGE_SIZE + PAGE_SIZE)
                    .map((v) => (
                      <tr key={v.id}>
                        <td>
                          <Link
                            className="vehicle-link"
                            to={`/vehicles/${v.id}`}
                          >
                            <img
                              src="/assets/vehicle.svg"
                              width="22"
                              height="22"
                              alt=""
                            />
                            {v.vin}
                          </Link>
                        </td>
                        <td>
                          <span className="version">{v.softwareVersion}</span>
                        </td>
                        <td>
                          <Status value={v.connectivityStatus} />
                        </td>
                        <td>
                          <Timestamp value={v.lastSeenAt} />
                        </td>
                        <td>
                          <Link
                            aria-label={`Inspect ${v.vin}`}
                            to={`/vehicles/${v.id}`}
                          >
                            ›
                          </Link>
                        </td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
            {!filtered.length && (
              <Empty title="No matching vehicles">
                Try another VIN or connectivity filter.
              </Empty>
            )}
            <div className="panel-footer">
              <span>
                {filtered.length
                  ? `${current * PAGE_SIZE + 1}–${Math.min((current + 1) * PAGE_SIZE, filtered.length)} of ${filtered.length}`
                  : "0 vehicles"}
              </span>
              <div className="actions">
                <button
                  disabled={current === 0}
                  onClick={() => setPage(current - 1)}
                >
                  Previous
                </button>
                <span>
                  {current + 1} / {pages}
                </span>
                <button
                  disabled={current >= pages - 1}
                  onClick={() => setPage(current + 1)}
                >
                  Next
                </button>
              </div>
            </div>
          </Panel>
        </>
      )}
      <Freshness value={query.updatedAt} />
    </>
  );
}
