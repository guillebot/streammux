import { Link } from "react-router-dom";
import type { JobEvent } from "./types";

function formatTime(iso: string): string {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

function sourceBadge(event: JobEvent): string {
  if (event.instanceId === "job-management-api") return "API";
  if (event.siteId) return "Orchestrator";
  return "System";
}

function attributesSummary(attributes: Record<string, unknown>): string {
  const entries = Object.entries(attributes);
  if (entries.length === 0) return "";
  return entries.map(([k, v]) => `${k}=${String(v)}`).join(", ");
}

interface JobEventTimelineProps {
  events: JobEvent[];
  emptyMessage?: string;
}

export function JobEventTimeline({ events, emptyMessage = "No events." }: JobEventTimelineProps) {
  if (events.length === 0) {
    return <p className="muted">{emptyMessage}</p>;
  }

  const sorted = [...events].sort((a, b) => b.eventTime.localeCompare(a.eventTime));

  return (
    <div className="table-wrap">
      <table className="job-table activity-table">
        <thead>
          <tr>
            <th>Time</th>
            <th>User</th>
            <th>Action</th>
            <th>Detail</th>
            <th>Site</th>
            <th>Source</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((event) => (
            <tr key={event.eventId}>
              <td className="mono">{formatTime(event.eventTime)}</td>
              <td>{event.actor ?? "—"}</td>
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
              <td>{sourceBadge(event)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

interface ActivityJobLinkProps {
  jobId: string;
}

export function ActivityJobLink({ jobId }: ActivityJobLinkProps) {
  if (jobId === "_platform") {
    return <span className="muted">Platform</span>;
  }
  return <Link to={`/job/${encodeURIComponent(jobId)}`}>{jobId}</Link>;
}
