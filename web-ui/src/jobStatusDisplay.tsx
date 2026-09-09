import type { HealthState, JobRuntimeStatus, LagMetrics } from "./types";
import { DEFAULT_LAG_WARN_THRESHOLD } from "./api/healthClient";

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

const COMPACT_UNITS: { threshold: number; suffix: string }[] = [
  { threshold: 1e12, suffix: "T" },
  { threshold: 1e9, suffix: "B" },
  { threshold: 1e6, suffix: "M" },
  { threshold: 1e3, suffix: "k" },
];

/** Short form for dense table cells: 196,127 -> "196k", 11,608,739 -> "11.6M". */
export function formatCompactCount(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return "—";
  const abs = Math.abs(n);
  const unit = COMPACT_UNITS.find((u) => abs >= u.threshold);
  if (!unit) return String(Math.round(n));
  const scaled = n / unit.threshold;
  // One decimal only while it buys precision; 196.8k reads better as 197k.
  const text = Math.abs(scaled) < 100 ? scaled.toFixed(1).replace(/\.0$/, "") : scaled.toFixed(0);
  return `${text}${unit.suffix}`;
}

export function formatRatePerSecond(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return "—";
  return `${n.toLocaleString()}/s`;
}

export function formatCompactRatePerSecond(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return "—";
  return `${formatCompactCount(n)}/s`;
}

export function formatOutputCountWithPercent(
  outputCount: number | null | undefined,
  inputCount: number | null | undefined,
  compact = false,
): string {
  const countStr = compact ? formatCompactCount(outputCount) : formatCount(outputCount);
  if (countStr === "—") return countStr;
  if (
    outputCount != null &&
    outputCount > 0 &&
    inputCount != null &&
    Number.isFinite(inputCount) &&
    inputCount > 0
  ) {
    const pct = Math.round((outputCount / inputCount) * 100);
    return `${countStr}(${pct}%)`;
  }
  return countStr;
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
  inputCountForPercent: number | null | undefined,
  compact: boolean,
): string | null {
  const rateFn = compact ? formatCompactRatePerSecond : formatRatePerSecond;
  const countFn = compact ? formatCompactCount : formatCount;
  const parts: string[] = [];
  if (rate != null && rate > 0) parts.push(rateFn(rate));
  if (count != null && count > 0) {
    parts.push(
      label === "out"
        ? formatOutputCountWithPercent(count, inputCountForPercent, compact)
        : countFn(count),
    );
  }
  if (includeLag && lag != null && lag > 0) parts.push(`lag ${countFn(lag)}`);
  if (parts.length === 0) return null;
  return `${label}  ${parts.join(" · ")}`;
}

export function formatLagMetricsSummary(
  lag: LagMetrics | null | undefined,
  compact = false,
): string {
  if (!lag) return "—";
  const input = formatTrafficSide(
    "in",
    lag.inputRatePerSecond,
    lag.inputCount,
    true,
    lag.inputLag,
    null,
    compact,
  );
  const output = formatTrafficSide(
    "out",
    lag.outputRatePerSecond,
    lag.outputCount,
    false,
    null,
    lag.inputCount,
    compact,
  );
  const lines = [input, output].filter((line): line is string => line != null);
  return lines.length > 0 ? lines.join("\n") : "—";
}

export function isHighLag(
  lag: LagMetrics | null | undefined,
  threshold: number = DEFAULT_LAG_WARN_THRESHOLD,
): boolean {
  return lag != null && Number.isFinite(lag.inputLag) && lag.inputLag >= threshold;
}

export function isUnhealthyJob(status: JobRuntimeStatus | null | undefined): boolean {
  return status?.health === "UNHEALTHY" || status?.state === "FAILED";
}
