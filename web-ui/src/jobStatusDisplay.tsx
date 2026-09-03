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
  return `${n.toLocaleString()}/s`;
}

export function hasTrafficMetrics(lag: LagMetrics | null | undefined): boolean {
  if (!lag) return false;
  return (
    lag.inputCount > 0 ||
    lag.inputRatePerSecond > 0 ||
    lag.outputCount > 0 ||
    lag.outputRatePerSecond > 0 ||
    lag.inputLag > 0
  );
}

export function statusDisplayLabel(status: JobRuntimeStatus | null | undefined): string {
  if (!status) return "Not reported";
  if (status.failureReason) return status.state;
  return status.state;
}

export function statusDisplayTitle(status: JobRuntimeStatus | null | undefined): string | undefined {
  return status?.failureReason ?? undefined;
}

function formatTrafficSide(
  label: "in" | "out",
  rate: number | null | undefined,
  count: number | null | undefined,
  includeLag: boolean,
  lag: number | null | undefined,
): string | null {
  const parts: string[] = [];
  if (rate != null && rate > 0) parts.push(formatRatePerSecond(rate));
  if (count != null && count > 0) parts.push(formatCount(count));
  if (includeLag && lag != null && lag > 0) parts.push(`lag ${formatCount(lag)}`);
  if (parts.length === 0) return null;
  return `${label}  ${parts.join(" · ")}`;
}

export function formatLagMetricsSummary(lag: LagMetrics | null | undefined): string {
  if (!lag) return "—";
  const input = formatTrafficSide("in", lag.inputRatePerSecond, lag.inputCount, true, lag.inputLag);
  const output = formatTrafficSide("out", lag.outputRatePerSecond, lag.outputCount, false, null);
  const lines = [input, output].filter((line): line is string => line != null);
  return lines.length > 0 ? lines.join("\n") : "—";
}
