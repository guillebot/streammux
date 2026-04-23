import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { deleteJob, getLease, getStatus, listJobs, updateJob } from "./api/client";
import { IconPause, IconPlay, IconTrash } from "./jobActionIcons";
import { InlineSpinner } from "./InlineSpinner";
import type { JobDefinition, JobLease, JobRuntimeStatus } from "./types";

const REFRESH_STORAGE_KEY = "streammux.jobList.refreshIntervalMs";

const REFRESH_OPTIONS: { label: string; ms: number }[] = [
  { label: "1 s", ms: 1_000 },
  { label: "5 s", ms: 5_000 },
  { label: "10 s", ms: 10_000 },
  { label: "30 s", ms: 30_000 },
  { label: "1 min", ms: 60_000 },
  { label: "2 min", ms: 120_000 },
  { label: "5 min", ms: 300_000 },
];

const DEFAULT_REFRESH_MS = 10_000;

const ALLOWED_MS = new Set(REFRESH_OPTIONS.map((o) => o.ms));

function readStoredRefreshMs(): number {
  try {
    const raw = localStorage.getItem(REFRESH_STORAGE_KEY);
    if (raw == null) return DEFAULT_REFRESH_MS;
    const n = Number(raw);
    if (!Number.isFinite(n) || !ALLOWED_MS.has(n)) return DEFAULT_REFRESH_MS;
    return n;
  } catch {
    return DEFAULT_REFRESH_MS;
  }
}

type RowAction = "play" | "pause" | "delete";

function orchestratorLabel(lease: JobLease | null | undefined): string {
  if (!lease) return "—";
  return `${lease.leaseOwnerSite} · ${lease.leaseOwnerInstance}`;
}

function orchestratorTitle(lease: JobLease | null | undefined): string | undefined {
  if (!lease) return undefined;
  const parts = [
    `Site ${lease.leaseOwnerSite}`,
    `Instance ${lease.leaseOwnerInstance}`,
    `Status ${lease.status}`,
    `Epoch ${lease.leaseEpoch}`,
  ];
  return parts.join(" · ");
}

/** Display instant to second precision (UTC), no fractional part. */
function formatLastSeen(iso: string | null | undefined): { label: string; full?: string } {
  if (iso == null || iso === "") return { label: "—" };
  const t = Date.parse(iso);
  if (!Number.isFinite(t)) return { label: "—" };
  const d = new Date(t);
  const label = `${d.toISOString().slice(0, 19)}Z`;
  return { label, full: iso };
}

export function JobsList() {
  const [jobs, setJobs] = useState<JobDefinition[] | null>(null);
  const [statuses, setStatuses] = useState<Record<string, JobRuntimeStatus | null>>({});
  const [leases, setLeases] = useState<Record<string, JobLease | null>>({});
  const [error, setError] = useState<string | null>(null);
  const [refreshMs, setRefreshMs] = useState(readStoredRefreshMs);
  const [rowBusy, setRowBusy] = useState<Partial<Record<string, RowAction>>>({});
  const [actionError, setActionError] = useState<string | null>(null);
  const loadInFlight = useRef(false);

  const load = useCallback(async () => {
    if (loadInFlight.current) return;
    loadInFlight.current = true;
    setError(null);
    try {
      const data = await listJobs();
      const sorted = data.sort((a, b) => a.jobId.localeCompare(b.jobId));
      setJobs(sorted);
      const detailResults = await Promise.all(
        sorted.map(async (job) => {
          const [status, lease] = await Promise.all([getStatus(job.jobId), getLease(job.jobId)]);
          return { jobId: job.jobId, status, lease };
        }),
      );
      setStatuses(Object.fromEntries(detailResults.map((row) => [row.jobId, row.status])));
      setLeases(Object.fromEntries(detailResults.map((row) => [row.jobId, row.lease])));
    } catch (e) {
      setJobs(null);
      setStatuses({});
      setLeases({});
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      loadInFlight.current = false;
    }
  }, []);

  useEffect(() => {
    void load();
    const tick = () => {
      if (document.visibilityState === "visible") void load();
    };
    const intervalId = window.setInterval(tick, refreshMs);
    const onVisibility = () => {
      if (document.visibilityState === "visible") void load();
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      window.clearInterval(intervalId);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [load, refreshMs]);

  const onRefreshIntervalChange = (ms: number) => {
    setRefreshMs(ms);
    try {
      localStorage.setItem(REFRESH_STORAGE_KEY, String(ms));
    } catch {
      /* ignore quota / private mode */
    }
  };

  const runRowAction = useCallback(
    async (job: JobDefinition, action: RowAction, work: () => Promise<void>) => {
      setActionError(null);
      setRowBusy((s) => ({ ...s, [job.jobId]: action }));
      try {
        await work();
        setActionError(null);
        await load();
      } catch (e) {
        setActionError(e instanceof Error ? e.message : String(e));
      } finally {
        setRowBusy((s) => {
          const next = { ...s };
          delete next[job.jobId];
          return next;
        });
      }
    },
    [load],
  );

  const onPlay = (job: JobDefinition) => {
    if (job.desiredState !== "PAUSED") return;
    void runRowAction(job, "play", () =>
      updateJob(job.jobId, { ...job, desiredState: "ACTIVE", updatedBy: "web-ui" }),
    );
  };

  const onPause = (job: JobDefinition) => {
    if (job.desiredState !== "ACTIVE") return;
    void runRowAction(job, "pause", () =>
      updateJob(job.jobId, { ...job, desiredState: "PAUSED", updatedBy: "web-ui" }),
    );
  };

  const onDelete = (job: JobDefinition) => {
    if (job.desiredState !== "ACTIVE" && job.desiredState !== "PAUSED") return;
    if (
      !window.confirm(
        `Delete job "${job.jobId}"? This removes it from the control plane (equivalent to API DELETE).`,
      )
    ) {
      return;
    }
    void runRowAction(job, "delete", () => deleteJob(job.jobId));
  };

  return (
    <div>
      <div className="btn-row refresh-controls">
        <button type="button" className="primary" onClick={() => void load()}>
          Refresh now
        </button>
        <label htmlFor="job-list-refresh">
          Auto-refresh
          <select
            id="job-list-refresh"
            className="select-inline"
            value={refreshMs}
            onChange={(e) => onRefreshIntervalChange(Number(e.target.value))}
          >
            {REFRESH_OPTIONS.map((o) => (
              <option key={o.ms} value={o.ms}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
        <span className="muted" style={{ fontSize: "0.85rem" }}>
          when tab is visible
        </span>
      </div>

      {error ? <div className="banner error">{error}</div> : null}
      {actionError ? <div className="banner error">{actionError}</div> : null}

      {jobs && jobs.length === 0 ? <p className="muted">No jobs yet.</p> : null}

      {jobs && jobs.length > 0 ? (
        <div className="table-wrap">
          <table className="job-table">
            <thead>
              <tr>
                <th className="col-actions">Actions</th>
                <th>Job ID</th>
                <th>Version</th>
                <th>Desired state</th>
                <th>Actual state</th>
                <th>Last seen</th>
                <th>Orchestrator</th>
                <th>Site affinity</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((j) => {
                const busy = rowBusy[j.jobId];
                const rowDisabled = Boolean(busy);
                const canPlay = j.desiredState === "PAUSED";
                const canPause = j.desiredState === "ACTIVE";
                const canDelete = j.desiredState === "ACTIVE" || j.desiredState === "PAUSED";
                const lease = leases[j.jobId];
                const status = statuses[j.jobId];
                const lastSeen = formatLastSeen(status?.lastHeartbeatAt);
                return (
                  <tr key={j.jobId}>
                    <td className="table-actions col-actions">
                      <div className="icon-btn-group" role="group" aria-label={`Actions for job ${j.jobId}`}>
                        <button
                          type="button"
                          className="icon-btn"
                          title="Play — set desired state ACTIVE"
                          aria-label={`Play job ${j.jobId}`}
                          disabled={!canPlay || rowDisabled}
                          onClick={() => onPlay(j)}
                        >
                          {busy === "play" ? <InlineSpinner /> : <IconPlay />}
                        </button>
                        <button
                          type="button"
                          className="icon-btn"
                          title="Pause — set desired state PAUSED"
                          aria-label={`Pause job ${j.jobId}`}
                          disabled={!canPause || rowDisabled}
                          onClick={() => onPause(j)}
                        >
                          {busy === "pause" ? <InlineSpinner /> : <IconPause />}
                        </button>
                        <button
                          type="button"
                          className="icon-btn danger"
                          title="Delete job"
                          aria-label={`Delete job ${j.jobId}`}
                          disabled={!canDelete || rowDisabled}
                          onClick={() => onDelete(j)}
                        >
                          {busy === "delete" ? <InlineSpinner /> : <IconTrash />}
                        </button>
                      </div>
                    </td>
                    <td>
                      <Link to={`/job/${encodeURIComponent(j.jobId)}`}>{j.jobId}</Link>
                    </td>
                    <td className="mono">{j.jobVersion}</td>
                    <td>{j.desiredState}</td>
                    <td>{status?.state ?? "Not reported"}</td>
                    <td className="mono" title={lastSeen.full}>
                      {lastSeen.label}
                    </td>
                    <td className="mono" title={orchestratorTitle(lease ?? undefined)}>
                      {orchestratorLabel(lease ?? undefined)}
                    </td>
                    <td className="mono">{j.siteAffinity}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : null}

      {jobs === null && !error ? <p className="muted">Loading…</p> : null}
    </div>
  );
}
