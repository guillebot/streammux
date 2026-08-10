import { useCallback, useEffect, useRef, useState } from "react";
import { listActivity } from "./api/activityClient";
import { ActivityJobLink } from "./JobEventTimeline";
import { EventTypeMultiSelect } from "./EventTypeMultiSelect";
import { InlineSpinner } from "./InlineSpinner";
import { EVENT_TYPES, type JobEvent } from "./types";

const REFRESH_MS = 10_000;
const FILTER_DEBOUNCE_MS = 300;

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

function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = window.setTimeout(() => setDebounced(value), delayMs);
    return () => window.clearTimeout(id);
  }, [value, delayMs]);
  return debounced;
}

export function Logs() {
  const [events, setEvents] = useState<JobEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [jobIdFilter, setJobIdFilter] = useState("");
  const [eventTypeFilter, setEventTypeFilter] = useState<string[]>([]);
  const [actorFilter, setActorFilter] = useState("");
  const [messageFilter, setMessageFilter] = useState("");

  // Text filters go through debouncing so we do not hit the API on every
  // keystroke. The multi-select changes commit immediately because the user
  // toggles discrete options rather than typing.
  const debouncedJobId = useDebouncedValue(jobIdFilter, FILTER_DEBOUNCE_MS);
  const debouncedActor = useDebouncedValue(actorFilter, FILTER_DEBOUNCE_MS);
  const debouncedMessage = useDebouncedValue(messageFilter, FILTER_DEBOUNCE_MS);

  const load = useCallback(async () => {
    setError(null);
    try {
      const rows = await listActivity({
        limit: 200,
        jobId: debouncedJobId.trim() || undefined,
        eventTypes: eventTypeFilter.length > 0 ? eventTypeFilter : undefined,
        actor: debouncedActor.trim() || undefined,
      });
      const messageNeedle = debouncedMessage.trim().toLowerCase();
      const filtered = messageNeedle
        ? rows.filter(
            (e) =>
              e.message.toLowerCase().includes(messageNeedle) ||
              (e.actor ?? "").toLowerCase().includes(messageNeedle),
          )
        : rows;
      setEvents(filtered);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [debouncedJobId, eventTypeFilter, debouncedActor, debouncedMessage]);

  // Refresh whenever the debounced filters change.
  useEffect(() => {
    void load();
  }, [load]);

  // Independent 10s poll that reads the current `load` via a ref so it does
  // not restart every time a filter changes.
  const loadRef = useRef(load);
  useEffect(() => {
    loadRef.current = load;
  }, [load]);
  useEffect(() => {
    const id = window.setInterval(() => void loadRef.current(), REFRESH_MS);
    return () => window.clearInterval(id);
  }, []);

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
          <EventTypeMultiSelect
            options={EVENT_TYPES}
            selected={eventTypeFilter}
            onChange={setEventTypeFilter}
            ariaLabel="Filter by event type"
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
        <button type="button" onClick={() => void loadRef.current()}>
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
