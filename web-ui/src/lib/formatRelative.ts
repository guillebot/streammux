function plural(n: number, unit: string): string {
  return `${n} ${unit}${n === 1 ? "" : "s"}`;
}

/**
 * Human-friendly relative time for job heartbeats and similar UI surfaces.
 * Examples: "12 seconds ago", "5 minutes ago", "2h 15m ago", "1d 6h ago".
 */
export function formatRelativeAgo(iso: string | null | undefined, now: Date = new Date()): string {
  if (iso == null || iso === "") return "—";
  const t = Date.parse(iso);
  if (!Number.isFinite(t)) return "—";

  const ageSec = Math.max(0, Math.floor((now.getTime() - t) / 1000));
  if (ageSec < 60) return `${plural(ageSec, "second")} ago`;

  const ageMin = Math.floor(ageSec / 60);
  if (ageSec < 3600) return `${plural(ageMin, "minute")} ago`;

  const hours = Math.floor(ageSec / 3600);
  const mins = Math.floor((ageSec % 3600) / 60);
  if (ageSec < 86400) {
    if (mins > 0) return `${hours}h ${mins}m ago`;
    return `${plural(hours, "hour")} ago`;
  }

  const days = Math.floor(ageSec / 86400);
  const remHours = Math.floor((ageSec % 86400) / 3600);
  if (remHours > 0) return `${days}d ${remHours}h ago`;
  return `${plural(days, "day")} ago`;
}

/** ISO instant for tooltips (second precision, UTC). */
export function formatIsoTooltip(iso: string): string {
  const t = Date.parse(iso);
  if (!Number.isFinite(t)) return iso;
  return `${new Date(t).toISOString().slice(0, 19)}Z`;
}
