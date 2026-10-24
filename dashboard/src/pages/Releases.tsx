import { useState } from "react";
import type { FormEvent } from "react";
import {
  Link,
  useNavigate,
  useParams,
  useSearchParams,
} from "react-router-dom";
import { releases } from "../api/releases";
import { vehicles } from "../api/vehicles";
import { usePolling } from "../api/usePolling";
import {
  Header,
  Panel,
  Loading,
  Empty,
  ErrorState,
  Timestamp,
  Meta,
  Freshness,
} from "../components/ui";
import { SoftwareDistribution } from "../components/SoftwareDistribution";
import { newest } from "../utils/domain";
export function Releases() {
  const q = usePolling((s) =>
    Promise.all([releases.list(s), vehicles.list(s)]),
  );
  const items = newest(q.data?.[0] ?? []);
  const latest = items[0];
  return (
    <>
      <Header
        eyebrow="SOFTWARE"
        title="Artifact catalog"
        description="Signed software releases ready for staged delivery."
        actions={
          <Link className="button accent" to="/releases/new">
            Create release
          </Link>
        }
      />
      <ErrorState message={q.error} retry={q.refresh} stale={!!q.data} />
      {!q.data ? (
        <Loading />
      ) : !latest ? (
        <Panel label="RELEASES">
          <Empty
            title="No software releases"
            to="/releases/new"
            action="Create release"
          >
            Register an artifact URL and SHA-256 checksum to prepare your first
            software journey.
          </Empty>
        </Panel>
      ) : (
        <>
          <div className="two-pane">
            <Panel label="LATEST RELEASE" title={`Release ${latest.version}`}>
              <p className="muted">
                {latest.description || "Signed OTA software artifact"}
              </p>
              <Meta label="Artifact">
                <span className="break">{latest.artifactUrl}</span>
              </Meta>
              <div className="split">
                <Meta label="Created">
                  <Timestamp value={latest.createdAt} />
                </Meta>
                <Link className="button" to={`/releases/${latest.id}`}>
                  Open release ›
                </Link>
              </div>
            </Panel>
            <Panel label="CATALOG" dark>
              <strong className="hero-number">{items.length}</strong>
              <p className="muted">registered releases</p>
              <hr />
              <p>{q.data[1].length} vehicles in the fleet</p>
              <p className="muted small">
                Signing is controlled by the backend. The dashboard does not
                cryptographically verify artifacts.
              </p>
            </Panel>
          </div>
          <Panel label="RELEASE LEDGER" title="Software releases">
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Version / release ID</th>
                    <th>Artifact</th>
                    <th>Created</th>
                    <th>Fleet adoption</th>
                    <th>Signing key</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((r) => (
                    <tr key={r.id}>
                      <td>
                        <Link to={`/releases/${r.id}`}>{r.version}</Link>
                        <small className="muted block">{r.id}</small>
                      </td>
                      <td className="break">{r.artifactUrl}</td>
                      <td>
                        <Timestamp value={r.createdAt} />
                      </td>
                      <td>
                        {
                          q.data?.[1].filter(
                            (v) => v.softwareVersion === r.version,
                          ).length
                        }{" "}
                        vehicles
                      </td>
                      <td>{r.signingKeyId}</td>
                    </tr>
                  ))}
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
export function ReleaseDetail() {
  const { id = "" } = useParams();
  const [params] = useSearchParams();
  const q = usePolling((s) =>
    Promise.all([releases.get(id, s), vehicles.list(s)]),
  );
  if (!q.data)
    return (
      <>
        <Header
          eyebrow="SOFTWARE / RELEASE"
          title="Release detail"
          description="Artifact metadata and fleet adoption."
        />
        <ErrorState message={q.error} retry={q.refresh} />
        <Loading />
      </>
    );
  const [r, fleet] = q.data;
  return (
    <>
      <Header
        eyebrow="SOFTWARE / RELEASE"
        title={`Release ${r.version}`}
        description="Backend-signed artifact and current fleet adoption."
        actions={
          <Link className="button accent" to={`/rollouts/new?release=${r.id}`}>
            Plan software journey
          </Link>
        }
      />
      {params.has("created") && (
        <div className="notice success" role="status">
          Release {r.version} created successfully. It is now available for
          rollout planning.
        </div>
      )}
      <ErrorState message={q.error} retry={q.refresh} stale />
      <div className="three-pane">
        <Panel label="RELEASE" dark>
          <strong className="hero-number">{r.version}</strong>
          <Meta label="Release ID">
            <code>{r.id}</code>
          </Meta>
          <Meta label="Artifact">
            <span className="break">{r.artifactUrl}</span>
          </Meta>
          <Meta label="SHA-256">
            <code>{r.checksum}</code>
          </Meta>
          <Meta label="Created">
            <Timestamp value={r.createdAt} />
          </Meta>
          <Meta label="Description">
            {r.description || "No description supplied."}
          </Meta>
          <Link className="button light" to="/releases">
            Back to releases
          </Link>
        </Panel>
        <Panel
          label="FLEET ADOPTION"
          title={`${fleet.filter((v) => v.softwareVersion === r.version).length} vehicles on ${r.version}`}
        >
          <SoftwareDistribution vehicles={fleet} />
        </Panel>
        <Panel label="SIGNING METADATA" title="Backend signature">
          <Meta label="Algorithm">{r.signatureAlgorithm}</Meta>
          <Meta label="Key identifier">{r.signingKeyId}</Meta>
          <p className="muted">
            Signing keys remain on the control plane. Vehicles verify the signed
            manifest before installation.
          </p>
          <hr />
          <Link className="button accent" to={`/rollouts/new?release=${r.id}`}>
            Plan software journey
          </Link>
        </Panel>
      </div>
      <Freshness value={q.updatedAt} />
    </>
  );
}
export function CreateRelease() {
  const navigate = useNavigate();
  const [form, setForm] = useState({
    version: "",
    artifactUrl: "",
    checksum: "",
    description: "",
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(undefined);
    try {
      const r = await releases.create(form);
      navigate(`/releases/${r.id}?created=1`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Release creation failed.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <Header
        eyebrow="SOFTWARE / NEW RELEASE"
        title="Create release"
        description="Register an OTA artifact before it can enter a staged rollout."
      />
      <div className="form-layout">
        <Panel label="RELEASE DETAILS" title="New software artifact">
          <form onSubmit={submit}>
            <fieldset disabled={busy}>
              <div className="field-grid">
                <label>
                  Version
                  <input
                    required
                    maxLength={50}
                    value={form.version}
                    onChange={(e) =>
                      setForm({ ...form, version: e.target.value })
                    }
                    placeholder="2.2.0"
                  />
                </label>
                <label>
                  Artifact URL
                  <input
                    required
                    type="url"
                    maxLength={500}
                    value={form.artifactUrl}
                    onChange={(e) =>
                      setForm({ ...form, artifactUrl: e.target.value })
                    }
                    placeholder="https://artifacts.example/vehicle.bin"
                  />
                </label>
              </div>
              <label>
                SHA-256 checksum
                <input
                  required
                  pattern="[0-9a-fA-F]{64}"
                  title="Exactly 64 hexadecimal SHA-256 characters"
                  maxLength={64}
                  value={form.checksum}
                  onChange={(e) =>
                    setForm({ ...form, checksum: e.target.value })
                  }
                />
              </label>
              <label>
                Description <span className="muted">(optional)</span>
                <textarea
                  maxLength={500}
                  rows={4}
                  value={form.description}
                  onChange={(e) =>
                    setForm({ ...form, description: e.target.value })
                  }
                />
              </label>
              {/^[0-9a-fA-F]{64}$/.test(form.checksum) && (
                <p className="notice success">
                  Checksum format valid. Artifact integrity is verified by
                  vehicles during download.
                </p>
              )}
              <ErrorState message={error} retry={() => setError(undefined)} />
              <div className="form-actions">
                <Link className="button light" to="/releases">
                  Cancel
                </Link>
                <button className="accent" type="submit" disabled={busy}>
                  {busy ? "Creating…" : "Create release"}
                </button>
              </div>
            </fieldset>
          </form>
        </Panel>
        <Panel label="RELEASE PREVIEW" dark>
          <strong className="hero-number">
            {form.version || "New release"}
          </strong>
          <p className="muted">candidate</p>
          <hr />
          <Meta label="Artifact">
            <span className="break">
              {form.artifactUrl || "Artifact URL required"}
            </span>
          </Meta>
          <Meta label="Signing">Signed by the backend on registration</Meta>
          <hr />
          <p>
            The release becomes selectable when planning a software journey.
            Creating a release does not start a rollout.
          </p>
        </Panel>
      </div>
    </>
  );
}
