import { useCallback, useEffect, useMemo, useRef, useState } from "react";
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
  renameJob,
  updateJob,
  validateJob,
} from "./api/client";
import { resolveActor } from "./actorCache";
import { JobEventTimeline } from "./JobEventTimeline";
import { InlineSpinner } from "./InlineSpinner";
import {
  JobHealthBadge,
  formatCount,
  formatRatePerSecond,
  hasTrafficMetrics,
  kafkaStreamsState,
} from "./jobStatusDisplay";
import { takeStashedJobDefinition } from "./jobBuilderStash";
import { JsonEditor } from "./JsonEditor";
import { useJobDefinitionSchema } from "./useJobDefinitionSchema";
import { extractPathFromMessage } from "./validationPathRange";
import { newJobTemplate } from "./templates";
import {
  JobHealthBadge,
  formatCount,
  formatRatePerSecond,
  hasTrafficMetrics,
  kafkaStreamsState,
} from "./jobStatusDisplay";
import type { JobDefinition, JobEvent, JobLease, JobRuntimeStatus } from "./types";

export function JobDetail() {
  const { jobId: rawJobId } = useParams();
  const navigate = useNavigate();
  const isNew = rawJobId === "new";
  const jobId = isNew ? null : rawJobId ?? null;

  const [jsonText, setJsonTextState] = useState("");
  const [loading, setLoading] = useState(!isNew);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [validation, setValidation] = useState<
    | { kind: "idle" }
    | { kind: "running" }
    | { kind: "ok" }
    | { kind: "fail"; path: string | null; message: string }
  >({ kind: "idle" });

  // Any edit to the JSON invalidates the last validation result and clears the
  // squiggle so the user isn't chasing a stale diagnostic.
  const setJsonText = useCallback((next: string) => {
    setJsonTextState(next);
    setValidation((prev) => (prev.kind === "idle" ? prev : { kind: "idle" }));
  }, []);

  const jsonParseError = useMemo(() => {
    if (!jsonText) return "Fix JSON syntax first";
    try {
      const parsed: unknown = JSON.parse(jsonText);
      if (typeof parsed !== "object" || parsed === null) return "JSON must be an object";
      return null;
    } catch (e) {
      return e instanceof Error ? e.message : "Invalid JSON";
    }
  }, [jsonText]);

  const [status, setStatus] = useState<JobRuntimeStatus | null | undefined>(undefined);
  const [lease, setLease] = useState<JobLease | null | undefined>(undefined);
  const [events, setEvents] = useState<JobEvent[] | null | undefined>(undefined);
  const [autoRefresh, setAutoRefresh] = useState(true);

  const { schema: jobDefinitionSchema } = useJobDefinitionSchema();

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

  const onValidateConfig = async () => {
    setValidation({ kind: "running" });
    try {
      const def = parseDefinition();
      const actor = await resolveActor();
      await validateJob({ ...def, updatedBy: actor });
      setValidation({ kind: "ok" });
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      const path = extractPathFromMessage(message);
      setValidation({ kind: "fail", path, message });
    }
  };

  const onSave = async () => {
    setNotice(null);
    setError(null);
    try {
      setBusyAction("save");
      const def = parseDefinition();
      const actor = await resolveActor();
      const withActor = { ...def, updatedBy: actor };
      // Pre-flight validation: run the same rules the API would apply on create/update
      // (topic allowlists, ROUTE_APP filter syntax, job-type config shape) so we fail
      // before anything is written to Kafka or the catalog.
      try {
        await validateJob(withActor);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
        return;
      }
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

      // Detect a jobId change in the edited JSON and route through the rename flow. The
      // update-in-place path below silently drops body.jobId, so without this branch the
      // rename would appear to succeed but the id wouldn't actually change.
      const parsedId = typeof def.jobId === "string" ? def.jobId.trim() : "";
      if (!parsedId) {
        setError("jobId cannot be blank");
        return;
      }
      if (parsedId !== jobId) {
        if (
          !window.confirm(
            `Rename job "${jobId}" to "${parsedId}"?\n\n` +
              "Runtime state (status, lease, events) and version history will not carry over, and any running instance will restart."
          )
        ) {
          return;
        }

        let current: JobDefinition;
        try {
          current = await getJob(jobId);
        } catch (e) {
          setError(`Rename aborted: could not load current definition for "${jobId}": ${e instanceof Error ? e.message : String(e)}`);
          return;
        }

        // Persist any co-edits (fields other than jobId) under the old key first, so
        // the rename picks them up when it copies current-under-new-key. PUT ignores
        // body.jobId, so we set it back to the old id defensively for the compare.
        const bodyForCompare = { ...def, jobId: current.jobId, jobVersion: current.jobVersion, updatedAt: current.updatedAt, updatedBy: current.updatedBy };
        const hasOtherEdits = JSON.stringify(bodyForCompare) !== JSON.stringify(current);
        let coEditsPersisted = false;
        if (hasOtherEdits) {
          try {
            const updated = await updateJob(jobId, { ...def, jobId: current.jobId, updatedBy: actor });
            setJsonText(JSON.stringify(updated, null, 2));
            coEditsPersisted = true;
          } catch (e) {
            setError(`Rename aborted: saving field edits under "${jobId}" failed: ${e instanceof Error ? e.message : String(e)}. Nothing was renamed.`);
            return;
          }
        }

        let renamed: JobDefinition;
        try {
          renamed = await renameJob(jobId, parsedId);
        } catch (e) {
          if (coEditsPersisted) {
            setError(
              `Field edits saved under "${jobId}", but renaming to "${parsedId}" failed: ${e instanceof Error ? e.message : String(e)}. ` +
                `The job is still "${jobId}". Pick a different new id and try again.`
            );
          } else {
            setError(`Rename to "${parsedId}" failed: ${e instanceof Error ? e.message : String(e)}. No changes were made.`);
          }
          return;
        }

        setJsonText(JSON.stringify(renamed, null, 2));
        try {
          const rows = await listCatalogEntries();
          const row = rows.find((r) => r.jobId === jobId);
          if (row) await updateCatalogEntry(row.id, row.title || parsedId, renamed);
        } catch (catErr) {
          setError(`Rename succeeded, but re-linking the catalog entry failed: ${catErr instanceof Error ? catErr.message : String(catErr)}. You may need to fix it manually.`);
        }
        setNotice(`Renamed ${jobId} → ${renamed.jobId}.`);
        navigate(`/job/${encodeURIComponent(renamed.jobId)}`, { replace: true });
        return;
      }

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
          <JsonEditor
            id="def-json"
            ariaLabel="Job definition (JSON)"
            value={jsonText}
            onChange={setJsonText}
            schema={jobDefinitionSchema}
            externalDiagnostic={
              validation.kind === "fail"
                ? { path: validation.path, message: validation.message }
                : null
            }
          />

          {validation.kind === "ok" ? (
            <div className="validation-banner ok">Definition is valid.</div>
          ) : validation.kind === "fail" ? (
            <div className="validation-banner fail">{validation.message}</div>
          ) : null}

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
            <button
              type="button"
              disabled={
                busyAction !== null || validation.kind === "running" || jsonParseError !== null
              }
              title={jsonParseError ?? undefined}
              onClick={() => void onValidateConfig()}
            >
              {validation.kind === "running" ? (
                <>
                  <InlineSpinner />
                  Validating...
                </>
              ) : (
                "Validate config"
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
                      <dd>
                        <JobHealthBadge health={status.health} />
                      </dd>
                      <dt>Kafka Streams</dt>
                      <dd className="mono">{kafkaStreamsState(status) ?? "—"}</dd>
                      <dt>Last heartbeat</dt>
                      <dd className="mono">{status.lastHeartbeatAt ?? "—"}</dd>
                      <dt>Worker</dt>
                      <dd className="mono">{status.workerMetadata?.topologyName ?? "—"}</dd>
                    </dl>
                    {status.state === "RUNNING" ||
                    status.state === "DEGRADED" ||
                    hasTrafficMetrics(status.lagMetrics) ? (
                      <>
                        <h3 className="panel-subhead">Traffic</h3>
                        <dl className="status-kv">
                          <dt>Input rate</dt>
                          <dd className="mono">{formatRatePerSecond(status.lagMetrics?.inputRatePerSecond)}</dd>
                          <dt>Input since start</dt>
                          <dd className="mono">{formatCount(status.lagMetrics?.inputCount)}</dd>
                          <dt>Input lag</dt>
                          <dd className="mono">{formatCount(status.lagMetrics?.inputLag)}</dd>
                          <dt>Output rate</dt>
                          <dd className="mono">{formatRatePerSecond(status.lagMetrics?.outputRatePerSecond)}</dd>
                          <dt>Output since start</dt>
                          <dd className="mono">{formatCount(status.lagMetrics?.outputCount)}</dd>
                        </dl>
                      </>
                    ) : (
                      <p className="muted" style={{ marginTop: "0.75rem", marginBottom: 0 }}>
                        No throughput metrics yet (job stopped or streams metrics unavailable).
                      </p>
                    )}
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
