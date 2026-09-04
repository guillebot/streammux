export function formatLastLogin(iso: string | null | undefined, nowMs = Date.now()): string {
  if (!iso) return "—";
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return iso;
  const deltaSec = Math.round((nowMs - then) / 1000);
  const abs = Math.abs(deltaSec);
  const future = deltaSec < 0;
  let label: string;
  if (abs < 60) label = `${abs}s`;
  else if (abs < 3600) label = `${Math.round(abs / 60)}m`;
  else if (abs < 86400) label = `${Math.round(abs / 3600)}h`;
  else label = `${Math.round(abs / 86400)}d`;
  return future ? `in ${label}` : `${label} ago`;
}

export function userStatus(enabled: boolean, lockedUntil: string | null | undefined, nowMs = Date.now()): string {
  if (!enabled) return "disabled";
  if (lockedUntil) {
    const until = Date.parse(lockedUntil);
    if (!Number.isNaN(until) && until > nowMs) return "locked";
  }
  return "active";
}
