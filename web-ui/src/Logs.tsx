import { useCallback, useEffect, useState } from "react";
import { listActivity } from "./api/activityClient";
import { ActivityJobLink } from "./JobEventTimeline";
import { InlineSpinner } from "./InlineSpinner";
import type { JobEvent } from "./types";

const REFRESH_MS = 10_000;

function formatTime(iso: string): string {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

function attributesSummary(attributes: Record<string, unknown>): string {
  const entries = Object.entries(attributes);
  if (entries.length === 0) return "";
  return entries.map(([k, v]) => `${k}=${String(v)}`).join(", ");
}

export function Logs() {
  const [events, setEvents] = useState<JobEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [jobIdFilter, setJobIdFilter] = useState("");
  const [eventTypeFilter, setEventTypeFilter] = useState("");
  const [actorFilter, setActorFilter] = useState("");
  const [messageFilter, setMessageFilter] = useState("");

  const load = useCallback(async () => {
    setError(null);
    try {
      const rows = await listActivity({
        limit: 200,
        jobId: jobIdFilter.trim() || undefined,
        eventType: eventTypeFilter.trim() || undefined,
        actor: actorFilter.trim() || undefined,
      });
      const filtered = messageFilter.trim()
        ? rows.filter(
            (e) =>
              e.message.toLowerCase().includes(messageFilter.trim().toLowerCase()) ||
              (e.actor ?? "").toLowerCase().includes(messageFilter.trim().toLowerCase()),
          )
        : rows;
      setEvents(filtered);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [jobIdFilter, eventTypeFilter, actorFilter, messageFilter]);

  useEffect(() => {
    void load();
    const id = window.setInterval(() => void load(), REFRESH_MS);
    return () => window.clearInterval(id);
  }, [load]);

  return (
    <div className="page page--wide">
      <h2 style={{ marginTop: 0 }}>Activity logs</h2>
      <p className="muted">
        Recent control-plane and runtime events. User column shows Authelia identity when proxied through OneLab.
      </p>

      <div className="filter-row">
        <label>
          Job ID
          <input type="text" value={jobIdFilter} onChange={(e) => setJobIdFilter(e.target.value)} placeholder="Filter job" />
        </label>
        <label>
          Event type
          <input
            type="text"
            value={eventTypeFilter}
            onChange={(e) => setEventTypeFilter(e.target.value)}
            placeholder="e.g. PAUSED"
          />
        </label>
        <label>
          User
          <input type="text" value={actorFilter} onChange={(e) => setActorFilter(e.target.value)} placeholder="Authelia user" />
        </label>
        <label>
          Search
          <input
            type="text"
            value={messageFilter}
            onChange={(e) => setMessageFilter(e.target.value)}
            placeholder="Message or user"
          />
        </label>
        <button type="button" onClick={() => void load()}>
          Refresh
        </button>
      </div>

      {error ? <div className="banner error">{error}</div> : null}
      {loading && events.length === 0 ? (
        <p className="muted">
          <InlineSpinner /> Loading…
        </p>
      ) : null}

      <div className="table-wrap">
        <table className="job-table activity-table">
          <thead>
            <tr>
              <th>Time</th>
              <th>User</th>
              <th>Job</th>
              <th>Action</th>
              <th>Detail</th>
              <th>Site</th>
            </tr>
          </thead>
          <tbody>
            {events.map((event) => (
              <tr key={event.eventId}>
                <td className="mono">{formatTime(event.eventTime)}</td>
                <td>{event.actor ?? "—"}</td>
                <td>
                  <ActivityJobLink jobId={event.jobId} />
                </td>
                <td>
                  <span className="event-type-badge">{event.eventType}</span>
                </td>
                <td>
                  {event.message}
                  {attributesSummary(event.attributes) ? (
                    <span className="muted"> ({attributesSummary(event.attributes)})</span>
                  ) : null}
                </td>
                <td className="mono">
                  {event.siteId ? `${event.siteId} · ${event.instanceId ?? "?"}` : "—"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {!loading && events.length === 0 ? <p className="muted">No matching activity.</p> : null}
    </div>
  );
}
