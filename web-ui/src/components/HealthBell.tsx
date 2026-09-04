import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import {
  getHealthIssues,
  getHealthSummary,
  type HealthIssue,
  type HealthSummary,
} from "../api/healthClient";

function bellTone(summary: HealthSummary | null): "ok" | "warn" | "bad" | "neutral" {
  if (!summary) return "neutral";
  if (summary.critical > 0) return "bad";
  if (summary.warning > 0) return "warn";
  if (summary.info > 0) return "ok";
  return "ok";
}

function severityClass(severity: HealthIssue["severity"]): string {
  if (severity === "critical") return "health-issue--critical";
  if (severity === "warning") return "health-issue--warning";
  return "health-issue--info";
}

export function HealthBell() {
  const [summary, setSummary] = useState<HealthSummary | null>(null);
  const [issues, setIssues] = useState<HealthIssue[]>([]);
  const [open, setOpen] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);

  const refresh = useCallback(async () => {
    try {
      const [s, i] = await Promise.all([getHealthSummary(), getHealthIssues()]);
      setSummary(s);
      setIssues(i.issues.slice(0, 5));
    } catch {
      setSummary(null);
      setIssues([]);
    }
  }, []);

  useEffect(() => {
    void refresh();
    const id = window.setInterval(() => void refresh(), 30_000);
    return () => window.clearInterval(id);
  }, [refresh]);

  useEffect(() => {
    if (!open) return;
    function onDocClick(e: MouseEvent) {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, [open]);

  const total = (summary?.critical ?? 0) + (summary?.warning ?? 0) + (summary?.info ?? 0);
  const tone = bellTone(summary);

  return (
    <div className="health-bell-wrap" ref={panelRef}>
      <button
        type="button"
        className={`health-bell-btn health-bell-btn--${tone}`}
        aria-label={total > 0 ? `${total} active issues` : "No active issues"}
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="health-bell-icon" aria-hidden>
          {summary && summary.prometheusReachable === false ? "🔕" : "🔔"}
        </span>
        {total > 0 ? <span className="health-bell-badge">{total}</span> : null}
      </button>
      {open ? (
        <div className="health-bell-popover" role="dialog" aria-label="Health issues">
          <div className="health-bell-popover-header">
            <strong>Platform health</strong>
            <Link to="/health" className="health-bell-link" onClick={() => setOpen(false)}>
              View all
            </Link>
          </div>
          {issues.length === 0 ? (
            <p className="muted health-bell-empty">No active issues.</p>
          ) : (
            <ul className="health-bell-list">
              {issues.map((issue) => (
                <li key={issue.id} className={`health-bell-item ${severityClass(issue.severity)}`}>
                  <span className="health-bell-item-title">{issue.title}</span>
                  {issue.detail ? <span className="muted health-bell-item-detail">{issue.detail}</span> : null}
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </div>
  );
}
