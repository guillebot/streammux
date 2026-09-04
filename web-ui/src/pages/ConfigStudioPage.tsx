import { useCallback, useEffect, useState } from "react";
import {
  getConfigStudioStatus,
  submitConfigStudio,
  syncConfigStudio,
  validateConfigStudio,
  type ConfigStudioStatus,
} from "../api/configStudioClient";

function ConfigStudioUnavailable({
  title,
  intro,
  issues,
  gitlabProjectUrl,
  onRetry,
}: {
  title: string;
  intro: string;
  issues: string[];
  gitlabProjectUrl?: string;
  onRetry?: () => void;
}) {
  return (
    <div className="page config-studio-page">
      <header className="page-header">
        <h1>Config Studio</h1>
        <p className="muted">
          Export live jobs to GitLab and reconcile Git → Kafka. Kafka remains the runtime source of truth.
        </p>
      </header>

      <section className="card config-studio-unavailable">
        <h2>{title}</h2>
        <p>{intro}</p>
        {issues.length > 0 ? (
          <ul className="config-studio-issues">
            {issues.map((issue) => (
              <li key={issue}>{issue}</li>
            ))}
          </ul>
        ) : null}
        {gitlabProjectUrl ? (
          <p className="muted" style={{ marginTop: "1rem" }}>
            GitLab project:{" "}
            <a href={gitlabProjectUrl} target="_blank" rel="noreferrer">
              {gitlabProjectUrl}
            </a>
          </p>
        ) : null}
        {onRetry ? (
          <div className="button-row" style={{ marginTop: "1rem" }}>
            <button type="button" className="btn" onClick={onRetry}>
              Retry
            </button>
          </div>
        ) : null}
      </section>
    </div>
  );
}

export function ConfigStudioPage() {
  const [status, setStatus] = useState<ConfigStudioStatus | null>(null);
  const [environment, setEnvironment] = useState("");
  const [gitRef, setGitRef] = useState("main");
  const [message, setMessage] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionResult, setActionResult] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getConfigStudioStatus();
      setStatus(data);
      setEnvironment(data.defaultEnvironment || data.allowedEnvironments[0] || "onelab");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load Config Studio");
      setStatus(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function runAction(label: string, fn: () => Promise<unknown>) {
    setBusy(true);
    setActionResult(null);
    setError(null);
    try {
      const result = await fn();
      setActionResult(`${label} OK\n${JSON.stringify(result, null, 2)}`);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : `${label} failed`);
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return (
      <div className="page">
        <h1>Config Studio</h1>
        <p className="muted">Loading…</p>
      </div>
    );
  }

  if (error && !status) {
    return (
      <ConfigStudioUnavailable
        title="Could not reach Config Studio"
        intro="The status endpoint did not respond. This is usually a network or authentication problem, not a missing GitLab token."
        issues={[error]}
        onRetry={() => void load()}
      />
    );
  }

  if (!status?.enabled) {
    return (
      <ConfigStudioUnavailable
        title="Config Studio is turned off"
        intro="This deployment is not configured to use Git-backed job definitions yet. Operators enable it after in-app auth and Postgres are live."
        issues={status?.configurationIssues ?? []}
      />
    );
  }

  if (!status.ready) {
    return (
      <ConfigStudioUnavailable
        title="GitLab integration is incomplete"
        intro="Config Studio is enabled, but the API cannot talk to GitLab yet. Fix the items below and redeploy job-management-api."
        issues={
          status.configurationIssues.length > 0
            ? status.configurationIssues
            : ["GitLab project id or token is missing from deployment configuration."]
        }
        gitlabProjectUrl={status.gitlabProjectUrl || undefined}
        onRetry={() => void load()}
      />
    );
  }

  return (
    <div className="page config-studio-page">
      <header className="page-header">
        <h1>Config Studio</h1>
        <p className="muted">
          Export live jobs to GitLab and reconcile Git → Kafka. Kafka remains the runtime source of truth.
        </p>
      </header>

      <section className="card">
        <h2>Overview</h2>
        <div className="health-grid">
          <div>
            <div className="health-kv-label">GitLab project</div>
            {status.gitlabProjectUrl ? (
              <a href={status.gitlabProjectUrl} target="_blank" rel="noreferrer">
                {status.gitlabProjectUrl}
              </a>
            ) : (
              <span className="muted">—</span>
            )}
          </div>
          <div>
            <div className="health-kv-label">Git HEAD ({gitRef})</div>
            <div className="mono">{status.gitHeadSha || "—"}</div>
          </div>
          <div>
            <div className="health-kv-label">Default environment</div>
            <div>{status.defaultEnvironment}</div>
          </div>
        </div>
        {Object.keys(status.lastSyncByEnv).length > 0 && (
          <div className="table-wrap" style={{ marginTop: "1rem" }}>
            <table className="job-table">
              <thead>
                <tr>
                  <th>Environment</th>
                  <th>Last synced SHA</th>
                  <th>Synced at</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(status.lastSyncByEnv).map(([env, row]) => (
                  <tr key={env}>
                    <td>{env}</td>
                    <td className="mono">{row.gitSha}</td>
                    <td>{row.syncedAt ? new Date(row.syncedAt).toLocaleString() : "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="card">
        <h2>Actions</h2>
        <div className="form-grid">
          <label>
            Environment
            <select value={environment} onChange={(e) => setEnvironment(e.target.value)}>
              {status.allowedEnvironments.map((env) => (
                <option key={env} value={env}>
                  {env}
                </option>
              ))}
            </select>
          </label>
          <label>
            Git ref
            <input value={gitRef} onChange={(e) => setGitRef(e.target.value)} placeholder="main" />
          </label>
          <label className="form-span-2">
            Submit message (optional)
            <input value={message} onChange={(e) => setMessage(e.target.value)} placeholder="Config Studio export" />
          </label>
        </div>
        <div className="button-row">
          <button
            type="button"
            className="btn"
            disabled={busy}
            onClick={() => void runAction("Validate", () => validateConfigStudio(environment, gitRef))}
          >
            Validate Git
          </button>
          <button
            type="button"
            className="btn"
            disabled={busy}
            onClick={() => void runAction("Dry-run sync", () => syncConfigStudio(environment, true, gitRef))}
          >
            Sync dry-run
          </button>
          <button
            type="button"
            className="btn btn-danger"
            disabled={busy}
            onClick={() => {
              if (!window.confirm(`Apply Git → Kafka for ${environment}?`)) return;
              void runAction("Sync", () => syncConfigStudio(environment, false, gitRef));
            }}
          >
            Sync live
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy}
            onClick={() => void runAction("Submit", () => submitConfigStudio(environment, message))}
          >
            Export to GitLab MR
          </button>
        </div>
        {error && <p className="error-text">{error}</p>}
        {actionResult && <pre className="mono action-result">{actionResult}</pre>}
      </section>
    </div>
  );
}
