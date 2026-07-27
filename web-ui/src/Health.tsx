import { useCallback, useEffect, useState } from "react";
import {
  getCatalogHealth,
  getPlatformHealth,
  worstStatus,
  type CatalogHealth,
  type HealthStatus,
  type PlatformHealth,
} from "./api/healthClient";
import { CommaWrapped } from "./CommaWrapped";

function statusLabel(status: HealthStatus): string {
  if (status === "UP") return "Healthy";
  if (status === "DEGRADED") return "Degraded";
  if (status === "DOWN") return "Down";
  return status;
}

function StatusBadge({ status }: { status: HealthStatus }) {
  const tone =
    status === "UP" ? "ok" : status === "DEGRADED" ? "warn" : status === "DOWN" ? "bad" : "neutral";
  return <span className={`health-badge health-badge--${tone}`}>{statusLabel(status)}</span>;
}

function formatCheckedAt(iso: string | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString();
}

export function Health() {
  const [platform, setPlatform] = useState<PlatformHealth | null>(null);
  const [catalog, setCatalog] = useState<CatalogHealth | null>(null);
  const [platformError, setPlatformError] = useState<string | null>(null);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setPlatformError(null);
    setCatalogError(null);

    const [platformResult, catalogResult] = await Promise.allSettled([
      getPlatformHealth(),
      getCatalogHealth(),
    ]);

    if (platformResult.status === "fulfilled") {
      setPlatform(platformResult.value);
    } else {
      setPlatform(null);
      setPlatformError(
        platformResult.reason instanceof Error ? platformResult.reason.message : String(platformResult.reason),
      );
    }

    if (catalogResult.status === "fulfilled") {
      setCatalog(catalogResult.value);
    } else {
      setCatalog(null);
      setCatalogError(
        catalogResult.reason instanceof Error ? catalogResult.reason.message : String(catalogResult.reason),
      );
    }

    setLoading(false);
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const overall = worstStatus(
    platform?.status ?? (platformError ? "DOWN" : "UP"),
    catalog?.status ?? (catalogError ? "DOWN" : "UP"),
  );

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1 className="page-title">Health</h1>
          <p className="page-subtitle muted">
            Kafka connectivity and Streammux module status. Last check:{" "}
            {platform ? formatCheckedAt(platform.checkedAt) : loading ? "…" : "—"}
          </p>
        </div>
        <StatusBadge status={overall} />
      </header>

      <div className="btn-row">
        <button type="button" onClick={() => void load()} disabled={loading}>
          Refresh
        </button>
      </div>

      {platformError ? <div className="banner error">job-management-api: {platformError}</div> : null}
      {catalogError ? <div className="banner error">job-catalog-api: {catalogError}</div> : null}

      <section className="panel">
        <h2>Modules</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Module</th>
                <th>Status</th>
                <th>Notes</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>{platform?.module.name ?? "job-management-api"}</td>
                <td>
                  <StatusBadge status={platform?.module.status ?? (platformError ? "DOWN" : "UP")} />
                </td>
                <td className="muted">REST API and Kafka read model</td>
              </tr>
              <tr>
                <td>{catalog?.module.name ?? "job-catalog-api"}</td>
                <td>
                  <StatusBadge status={catalog?.module.status ?? (catalogError ? "DOWN" : "UP")} />
                </td>
                <td className="muted">
                  {catalog ? `${catalog.catalog.entryCount} catalog entries` : "Job definition catalog"}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel">
        <h2>Kafka</h2>
        {platform ? (
          <>
            <div className="health-kv-grid">
              <div>
                <div className="health-kv-label">Connectivity</div>
                <StatusBadge status={platform.kafka.status} />
              </div>
              <div>
                <div className="health-kv-label">Bootstrap servers</div>
                <CommaWrapped value={platform.kafka.bootstrapServers} />
              </div>
              <div>
                <div className="health-kv-label">Cluster ID</div>
                <div className="mono">{platform.kafka.clusterId ?? "—"}</div>
              </div>
              <div>
                <div className="health-kv-label">Brokers</div>
                <div>{platform.kafka.brokerCount}</div>
              </div>
            </div>
            {platform.kafka.detail ? (
              <p className="banner error" style={{ marginTop: "0.75rem" }}>
                {platform.kafka.detail}
              </p>
            ) : null}
            <div className="table-wrap" style={{ marginTop: "0.75rem" }}>
              <table>
                <thead>
                  <tr>
                    <th>Topic key</th>
                    <th>Name</th>
                    <th>Exists</th>
                    <th>Cleanup policy</th>
                    <th>Expected</th>
                    <th>OK</th>
                  </tr>
                </thead>
                <tbody>
                  {platform.kafka.topics.map((topic) => (
                    <tr key={topic.key}>
                      <td className="mono">{topic.key}</td>
                      <td className="mono">{topic.name}</td>
                      <td>{topic.exists ? "Yes" : "No"}</td>
                      <td className="mono">{topic.cleanupPolicy ?? "—"}</td>
                      <td className="mono">{topic.expected}</td>
                      <td>{topic.ok ? "Yes" : "No"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {catalog && !catalogError ? (
              <>
                <p className="muted" style={{ marginTop: "0.75rem" }}>
                  Catalog topic <span className="mono">{catalog.kafka.topic}</span> on the same brokers (
                  <StatusBadge status={catalog.kafka.status} />).
                </p>
                {catalog.kafka.topics?.length ? (
                  <div className="table-wrap" style={{ marginTop: "0.75rem" }}>
                    <table>
                      <thead>
                        <tr>
                          <th>Topic key</th>
                          <th>Name</th>
                          <th>Exists</th>
                          <th>Cleanup policy</th>
                          <th>Expected</th>
                          <th>OK</th>
                        </tr>
                      </thead>
                      <tbody>
                        {catalog.kafka.topics.map((topic) => (
                          <tr key={topic.key}>
                            <td className="mono">{topic.key}</td>
                            <td className="mono">{topic.name}</td>
                            <td>{topic.exists ? "Yes" : "No"}</td>
                            <td className="mono">{topic.cleanupPolicy ?? "—"}</td>
                            <td className="mono">{topic.expected}</td>
                            <td>{topic.ok ? "Yes" : "No"}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : null}
                {catalog.kafka.detail ? (
                  <p className="banner error" style={{ marginTop: "0.75rem" }}>
                    {catalog.kafka.detail}
                  </p>
                ) : null}
              </>
            ) : null}
          </>
        ) : loading ? (
          <p className="muted">Loading Kafka health…</p>
        ) : (
          <p className="muted">Kafka health unavailable.</p>
        )}
      </section>

      <section className="panel">
        <h2>Read model (job-management-api)</h2>
        {platform ? (
          <div className="health-kv-grid">
            <div>
              <div className="health-kv-label">Jobs</div>
              <div>{platform.readModel.jobCount}</div>
            </div>
            <div>
              <div className="health-kv-label">Leases</div>
              <div>{platform.readModel.leaseCount}</div>
            </div>
            <div>
              <div className="health-kv-label">Statuses</div>
              <div>{platform.readModel.statusCount}</div>
            </div>
            <div>
              <div className="health-kv-label">Jobs with events</div>
              <div>{platform.readModel.eventJobCount}</div>
            </div>
          </div>
        ) : loading ? (
          <p className="muted">Loading read-model stats…</p>
        ) : (
          <p className="muted">Read-model stats unavailable.</p>
        )}
      </section>
    </div>
  );
}
