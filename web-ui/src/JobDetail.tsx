import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  createCatalogEntry,
  listCatalogEntries,
  updateCatalogEntry,
} from "./api/catalogClient";
import {
  createJob,
  deleteJob,
  getEvents,
  getJob,
  getLease,
  getStatus,
  updateJob,
} from "./api/client";
import { resolveActor } from "./actorCache";
import { JobEventTimeline } from "./JobEventTimeline";
import { InlineSpinner } from "./InlineSpinner";
import { takeStashedJobDefinition } from "./jobBuilderStash";
import { newJobTemplate } from "./templates";
import type { JobDefinition, JobEvent, JobLease, JobRuntimeStatus } from "./types";

export function JobDetail() {
  const { jobId: rawJobId } = useParams();
  const navigate = useNavigate();
  const isNew = rawJobId === "new";
  const jobId = isNew ? null : rawJobId ?? null;

  const [jsonText, setJsonText] = useState("");
  const [loading, setLoading] = useState(!isNew);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyAction, setBusyAction] = useState<string | null>(null);

  const [status, setStatus] = useState<JobRuntimeStatus | null | undefined>(undefined);
  const [lease, setLease] = useState<JobLease | null | undefined>(undefined);
  const [events, setEvents] = useState<JobEvent[] | null | undefined>(undefined);
  const [autoRefresh, setAutoRefresh] = useState(true);

  const newJobSeededRef = useRef(false);

  const loadDefinition = useCallback(async () => {
    if (!jobId) return;
    setLoading(true);
    setError(null);
    try {
      const def = await getJob(jobId);
      setJsonText(JSON.stringify(def, null, 2));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [jobId]);

  const loadProjections = useCallback(async () => {
    if (!jobId) return;
    setError(null);
    try {
      const [s, l, ev] = await Promise.all([getStatus(jobId), getLease(jobId), getEvents(jobId)]);
      setStatus(s);
      setLease(l);
      setEvents(ev);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [jobId]);

  useEffect(() => {
    if (isNew) {
      if (!newJobSeededRef.current) {
        newJobSeededRef.current = true;
        const fromBuilder = takeStashedJobDefinition();
        setJsonText(JSON.stringify(fromBuilder ?? newJobTemplate(), null, 2));
      }
      setStatus(undefined);
      setLease(undefined);
      setEvents(undefined);
      setLoading(false);
      return;
    }
    newJobSeededRef.current = false;
    if (jobId) void loadDefinition();
  }, [isNew, jobId, loadDefinition]);

  useEffect(() => {
    if (jobId && !isNew) void loadProjections();
  }, [jobId, isNew, loadProjections]);

  useEffect(() => {
    if (!jobId || isNew || !autoRefresh) return;
    const id = window.setInterval(() => void loadProjections(), 10_000);
    return () => window.clearInterval(id);
  }, [jobId, isNew, autoRefresh, loadProjections]);

  const parseDefinition = (): JobDefinition => {
    const parsed: unknown = JSON.parse(jsonText);
    if (typeof parsed !== "object" || parsed === null) throw new Error("JSON must be an object");
    return parsed as JobDefinition;
  };

  const onSave = async () => {
    setNotice(null);
    setError(null);
    try {
      setBusyAction("save");
      const def = parseDefinition();
      const actor = await resolveActor();
      const withActor = { ...def, updatedBy: actor };
      if (isNew) {
        const created = await createJob(withActor);
        try {
          await createCatalogEntry(created.jobId, created);
          setNotice(`Created job ${created.jobId} and saved to catalog.`);
        } catch (catErr) {
          setNotice(`Created job ${created.jobId}.`);
          setError(catErr instanceof Error ? `Catalog: ${catErr.message}` : `Catalog: ${String(catErr)}`);
        }
        navigate(`/job/${encodeURIComponent(created.jobId)}`, { replace: true });
        return;
      }
      if (!jobId) return;
      const updated = await updateJob(jobId, withActor);
      setJsonText(JSON.stringify(updated, null, 2));
      try {
        const catalogRows = await listCatalogEntries();
        const row = catalogRows.find((r) => r.jobId === jobId);
        if (row) {
          await updateCatalogEntry(row.id, row.title || jobId, updated);
          setNotice("Saved and updated catalog entry.");
        } else {
          setNotice("Saved.");
        }
      } catch (catErr) {
        setNotice("Saved.");
        setError(catErr instanceof Error ? `Catalog: ${catErr.message}` : `Catalog: ${String(catErr)}`);
      }
      void loadProjections();
    } catch (e) {
      if (e instanceof SyntaxError) setError(`Invalid JSON: ${e.message}`);
      else setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyAction(null);
    }
  };

  const setDesiredState = async (desiredState: "ACTIVE" | "PAUSED", label: string) => {
    if (!jobId) return;
    setNotice(null);
    setError(null);
    try {
      setBusyAction(label.toLowerCase());
      const def = await getJob(jobId);
      const actor = await resolveActor();
      await updateJob(jobId, { ...def, desiredState, updatedBy: actor });
      setNotice(`${label} accepted.`);
      void loadProjections();
      void loadDefinition();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyAction(null);
    }
  };

  const onRestart = async () => {
    if (!jobId) return;
    setNotice(null);
    setError(null);
    try {
      setBusyAction("restart");
      const def = await getJob(jobId);
      const actor = await resolveActor();
      await updateJob(jobId, { ...def, desiredState: "PAUSED", updatedBy: actor });
      await updateJob(jobId, { ...def, desiredState: "ACTIVE", updatedBy: actor });
      setNotice("Restart accepted (paused then resumed).");
      void loadProjections();
      void loadDefinition();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyAction(null);
    }
  };

  const onDelete = async () => {
    if (!jobId) return;
    if (!window.confirm(`Delete job ${jobId}?`)) return;
    setNotice(null);
    setError(null);
    try {
      setBusyAction("delete");
      await deleteJob(jobId);
      navigate("/", { replace: true });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyAction(null);
    }
  };

  if (!isNew && !jobId) {
    return (
      <div className="page">
        <p className="muted">Missing job id.</p>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="back-row">
        <Link to="/">← Jobs</Link>
      </div>

      <h2 style={{ marginTop: 0 }}>{isNew ? "New job" : jobId}</h2>

      {loading ? <p className="muted">Loading…</p> : null}
      {error ? <div className="banner error">{error}</div> : null}
      {notice ? <div className="banner success">{notice}</div> : null}

      {!loading || isNew ? (
        <>
          <label className="muted" htmlFor="def-json">
            Job definition (JSON)
          </label>
          <textarea
            id="def-json"
            className="json-editor mono"
            spellCheck={false}
            value={jsonText}
            onChange={(e) => setJsonText(e.target.value)}
          />

          <div className="btn-row">
            <button type="button" className="primary" disabled={busyAction !== null} onClick={() => void onSave()}>
              {busyAction === "save" ? (
                <>
                  <InlineSpinner />
                  Saving...
                </>
              ) : isNew ? (
                "Create"
              ) : (
                "Save changes"
              )}
            </button>
            {!isNew && jobId ? (
              <>
                <button type="button" disabled={busyAction !== null} onClick={() => void setDesiredState("PAUSED", "Pause")}>
                  {busyAction === "pause" ? (
                    <>
                      <InlineSpinner />
                      Pausing...
                    </>
                  ) : (
                    "Pause"
                  )}
                </button>
                <button type="button" disabled={busyAction !== null} onClick={() => void setDesiredState("ACTIVE", "Resume")}>
                  {busyAction === "resume" ? (
                    <>
                      <InlineSpinner />
                      Resuming...
                    </>
                  ) : (
                    "Resume"
                  )}
                </button>
                <button type="button" disabled={busyAction !== null} onClick={() => void onRestart()}>
                  {busyAction === "restart" ? (
                    <>
                      <InlineSpinner />
                      Restarting...
                    </>
                  ) : (
                    "Restart"
                  )}
                </button>
                <label className="inline-check">
                  <input type="checkbox" checked={autoRefresh} onChange={(e) => setAutoRefresh(e.target.checked)} />
                  Auto-refresh
                </label>
                <button type="button" disabled={busyAction !== null} onClick={() => void loadProjections()}>
                  Refresh status
                </button>
                <button type="button" className="danger" disabled={busyAction !== null} onClick={() => void onDelete()}>
                  {busyAction === "delete" ? (
                    <>
                      <InlineSpinner />
                      Deleting...
                    </>
                  ) : (
                    "Delete"
                  )}
                </button>
              </>
            ) : null}
          </div>

          {!isNew && jobId ? (
            <>
              <div className="panel">
                <h2>Runtime status</h2>
                {status === undefined ? (
                  <p className="muted">Loading…</p>
                ) : status === null ? (
                  <p className="muted">No status projected yet.</p>
                ) : (
                  <>
                    {status.failureReason ? (
                      <div className="banner error">Failure: {status.failureReason}</div>
                    ) : null}
                    <dl className="status-kv">
                      <dt>State</dt>
                      <dd>{status.state}</dd>
                      <dt>Health</dt>
                      <dd>{status.health}</dd>
                      <dt>Last heartbeat</dt>
                      <dd className="mono">{status.lastHeartbeatAt ?? "—"}</dd>
                      <dt>Worker</dt>
                      <dd className="mono">{status.workerMetadata?.topologyName ?? "—"}</dd>
                    </dl>
                  </>
                )}
              </div>

              <div className="panel">
                <h2>Lease</h2>
                {lease === undefined ? (
                  <p className="muted">Loading…</p>
                ) : lease === null ? (
                  <p className="muted">No lease projected yet.</p>
                ) : (
                  <pre className="pre-block mono">{JSON.stringify(lease, null, 2)}</pre>
                )}
              </div>

              <div className="panel">
                <h2>Events</h2>
                {events === undefined ? (
                  <p className="muted">Loading…</p>
                ) : (
                  <JobEventTimeline events={events} />
                )}
              </div>
            </>
          ) : null}
        </>
      ) : null}
    </div>
  );
}
