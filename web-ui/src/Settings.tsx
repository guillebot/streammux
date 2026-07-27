import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  formatSettingsList,
  getCatalogSettings,
  getPlatformSettings,
  type CatalogSettings,
  type PlatformSettings,
} from "./api/settingsClient";
import { CommaWrapped } from "./CommaWrapped";
import { appVersion } from "./version";

function formatLoadedAt(iso: string | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString();
}

function SettingsKv({
  label,
  value,
  wrapCommas,
}: {
  label: string;
  value: string | number | boolean;
  wrapCommas?: boolean;
}) {
  const display = typeof value === "boolean" ? (value ? "Yes" : "No") : value;
  return (
    <div>
      <div className="health-kv-label">{label}</div>
      {wrapCommas && typeof display === "string" ? (
        <CommaWrapped value={display} />
      ) : (
        <div className="mono">{display}</div>
      )}
    </div>
  );
}

export function Settings() {
  const [platform, setPlatform] = useState<PlatformSettings | null>(null);
  const [catalog, setCatalog] = useState<CatalogSettings | null>(null);
  const [platformError, setPlatformError] = useState<string | null>(null);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setPlatformError(null);
    setCatalogError(null);

    const [platformResult, catalogResult] = await Promise.allSettled([
      getPlatformSettings(),
      getCatalogSettings(),
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

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1 className="page-title">Settings</h1>
          <p className="page-subtitle muted">
            Non-secret runtime configuration. Last loaded:{" "}
            {platform ? formatLoadedAt(platform.loadedAt) : loading ? "…" : "—"}
          </p>
        </div>
      </header>

      <div className="btn-row">
        <button type="button" onClick={() => void load()} disabled={loading}>
          Refresh
        </button>
      </div>

      {platformError ? <div className="banner error">job-management-api: {platformError}</div> : null}
      {catalogError ? <div className="banner error">job-catalog-api: {catalogError}</div> : null}

      <section className="panel">
        <h2>Web console</h2>
        <div className="health-kv-grid">
          <SettingsKv label="Release version" value={appVersion} />
        </div>
      </section>

      <section className="panel">
        <h2>job-management-api</h2>
        {platform ? (
          <>
            <div className="health-kv-grid">
              <SettingsKv label="Application name" value={platform.module.applicationName} />
              <SettingsKv label="Bootstrap servers" value={platform.kafka.bootstrapServers} wrapCommas />
              <SettingsKv label="Consumer group ID" value={platform.kafka.consumerGroupId} />
              <SettingsKv
                label="Exposed actuator endpoints"
                value={formatSettingsList(platform.api.exposedActuatorEndpoints)}
              />
              <SettingsKv label="Springdoc show actuator" value={platform.api.springdocShowActuator} />
            </div>

            <h3 style={{ marginTop: "1rem" }}>Streammux topics</h3>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Key</th>
                    <th>Topic name</th>
                  </tr>
                </thead>
                <tbody>
                  {(
                    [
                      ["jobDefinitions", platform.topics.jobDefinitions],
                      ["jobLeases", platform.topics.jobLeases],
                      ["jobStatus", platform.topics.jobStatus],
                      ["jobEvents", platform.topics.jobEvents],
                      ["jobCommands", platform.topics.jobCommands],
                    ] as const
                  ).map(([key, name]) => (
                    <tr key={key}>
                      <td className="mono">{key}</td>
                      <td className="mono">{name}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <h3 style={{ marginTop: "1rem" }}>Topic validation allowlists</h3>
            <div className="health-kv-grid">
              <SettingsKv
                label="Allowed input topics"
                value={formatSettingsList(platform.validation.allowedInputTopics)}
              />
              <SettingsKv
                label="Allowed input prefixes"
                value={formatSettingsList(platform.validation.allowedInputTopicPrefixes)}
              />
              <SettingsKv
                label="Allowed output topics"
                value={formatSettingsList(platform.validation.allowedOutputTopics)}
              />
              <SettingsKv
                label="Allowed output prefixes"
                value={formatSettingsList(platform.validation.allowedOutputTopicPrefixes)}
              />
            </div>
          </>
        ) : loading ? (
          <p className="muted">Loading platform settings…</p>
        ) : (
          <p className="muted">Platform settings unavailable.</p>
        )}
      </section>

      <section className="panel">
        <h2>job-catalog-api</h2>
        {catalog ? (
          <div className="health-kv-grid">
            <SettingsKv label="Module" value={catalog.module.name} />
            <SettingsKv label="Bootstrap servers" value={catalog.kafka.bootstrapServers} wrapCommas />
            <SettingsKv label="Catalog topic" value={catalog.kafka.topic} />
            <SettingsKv label="Kafka client ID" value={catalog.kafka.clientId} />
            <SettingsKv label="Job management API URL" value={catalog.jobManagementApiUrl} />
            <SettingsKv label="Auto-create topic" value={catalog.topicConfig.createTopic} />
            <SettingsKv label="Topic partitions" value={catalog.topicConfig.partitions} />
            <SettingsKv label="Replication factor" value={catalog.topicConfig.replicationFactor} />
          </div>
        ) : loading ? (
          <p className="muted">Loading catalog settings…</p>
        ) : (
          <p className="muted">Catalog settings unavailable.</p>
        )}
      </section>

      <section className="panel">
        <h2>Documentation</h2>
        <p className="muted">
          In-app guides: <Link to="/docs">Documentation</Link>
          {" · "}
          <Link to="/docs/api">API reference</Link>
        </p>
        <p className="muted">
          OpenAPI:{" "}
          <a href="/swagger-ui/index.html" target="_blank" rel="noreferrer">
            Swagger UI
          </a>
        </p>
      </section>
    </div>
  );
}
