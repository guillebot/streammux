import type { HealthState, JobRuntimeStatus, LagMetrics } from "./types";

export function jobHealthBadgeTone(
  health: HealthState | null | undefined,
): "ok" | "warn" | "bad" | "neutral" {
  if (health === "HEALTHY") return "ok";
  if (health === "DEGRADED") return "warn";
  if (health === "UNHEALTHY") return "bad";
  return "neutral";
}

export function jobHealthLabel(health: HealthState | null | undefined): string {
  if (health == null || health === "") return "Unknown";
  return health.charAt(0) + health.slice(1).toLowerCase();
}

export function JobHealthBadge({ health }: { health: HealthState | null | undefined }) {
  const tone = jobHealthBadgeTone(health);
  return <span className={`health-badge health-badge--${tone}`}>{jobHealthLabel(health)}</span>;
}

export function kafkaStreamsState(status: JobRuntimeStatus | null | undefined): string | null {
  if (!status?.workerMetadata) return null;
  const fromAttrs = status.workerMetadata.attributes?.kafkaStreamsState;
  if (typeof fromAttrs === "string" && fromAttrs !== "") return fromAttrs;
  const local = status.workerMetadata.localState;
  if (local != null && local !== "") return local;
  return null;
}

export function formatCount(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return "—";
  return n.toLocaleString();
}

export function formatRatePerSecond(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return "—";
  return `${n.toLocaleString()} /s`;
}

export function hasTrafficMetrics(lag: LagMetrics | null | undefined): boolean {
  if (!lag) return false;
  return lag.processedCount > 0 || lag.outputRatePerSecond > 0 || lag.inputLag > 0;
}

export function formatLagMetricsSummary(lag: LagMetrics | null | undefined): string {
  if (!lag) return "—";
  const parts: string[] = [];
  if (lag.outputRatePerSecond > 0) parts.push(formatRatePerSecond(lag.outputRatePerSecond));
  if (lag.processedCount > 0) parts.push(`${formatCount(lag.processedCount)} total`);
  if (lag.inputLag > 0) parts.push(`lag ${formatCount(lag.inputLag)}`);
  return parts.length > 0 ? parts.join(" · ") : "—";
}
