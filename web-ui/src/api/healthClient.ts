import { apiFetch } from "./http";

export type HealthStatus = "UP" | "DEGRADED" | "DOWN" | string;

export interface ModuleHealth {
  name: string;
  status: HealthStatus;
}

export interface TopicPresence {
  key: string;
  name: string;
  exists: boolean;
  cleanupPolicy: string | null;
  expected: string;
  ok: boolean;
}

export interface KafkaHealth {
  status: HealthStatus;
  bootstrapServers: string;
  clusterId: string | null;
  brokerCount: number;
  topics: TopicPresence[];
  detail?: string | null;
}

export interface ReadModelHealth {
  jobCount: number;
  leaseCount: number;
  statusCount: number;
  eventJobCount: number;
}

export interface PlatformHealth {
  status: HealthStatus;
  checkedAt: string;
  module: ModuleHealth;
  kafka: KafkaHealth;
  readModel: ReadModelHealth;
}

export interface CatalogHealth {
  status: HealthStatus;
  module: ModuleHealth;
  kafka: {
    status: HealthStatus;
    bootstrapServers: string;
    topic: string;
    topics?: TopicPresence[];
    detail?: string | null;
  };
  catalog: {
    entryCount: number;
  };
}

export interface McpHealth {
  status: HealthStatus;
  checkedAt: string;
  module: ModuleHealth;
  body: string;
}

async function readJson<T>(response: Response): Promise<T> {
  const text = await response.text();
  if (!text.trim()) {
    throw new Error(`${response.status} empty response`);
  }
  return JSON.parse(text) as T;
}

async function handleError(response: Response): Promise<never> {
  let detail = response.statusText;
  try {
    const text = await response.text();
    if (text) detail = text.length > 200 ? `${text.slice(0, 200)}…` : text;
  } catch {
    /* ignore */
  }
  throw new Error(`${response.status} ${detail}`);
}

export async function getPlatformHealth(): Promise<PlatformHealth> {
  const res = await apiFetch("/jobs/meta/health");
  if (!res.ok) await handleError(res);
  return readJson<PlatformHealth>(res);
}

export async function getCatalogHealth(): Promise<CatalogHealth> {
  const res = await apiFetch("/catalog/health");
  if (!res.ok) await handleError(res);
  return readJson<CatalogHealth>(res);
}

export async function getMcpHealth(): Promise<McpHealth> {
  const res = await apiFetch("/health/mcp");
  if (!res.ok) await handleError(res);
  const body = (await res.text()).trim();
  const status: HealthStatus = body === "ok" ? "UP" : "DEGRADED";
  return {
    status,
    checkedAt: new Date().toISOString(),
    module: { name: "streammux-mcp", status },
    body: body || "—",
  };
}

export function worstStatus(...statuses: HealthStatus[]): HealthStatus {
  if (statuses.some((s) => s === "DOWN")) return "DOWN";
  if (statuses.some((s) => s === "DEGRADED")) return "DEGRADED";
  return "UP";
}

export type IssueSeverity = "critical" | "warning" | "info";

export interface HealthSummary {
  checkedAt: string;
  critical: number;
  warning: number;
  info: number;
  prometheusReachable: boolean;
}

export interface HealthIssue {
  id: string;
  severity: IssueSeverity;
  title: string;
  detail: string | null;
}

export interface HealthIssuesResponse {
  checkedAt: string;
  issues: HealthIssue[];
}

export async function getHealthSummary(): Promise<HealthSummary> {
  const res = await apiFetch("/jobs/meta/health/summary");
  if (!res.ok) await handleError(res);
  return readJson<HealthSummary>(res);
}

export async function getHealthIssues(): Promise<HealthIssuesResponse> {
  const res = await apiFetch("/jobs/meta/health/issues");
  if (!res.ok) await handleError(res);
  const raw = await readJson<{
    checkedAt: string;
    issues: Array<{ id: string; severity: string; title: string; detail: string | null }>;
  }>(res);
  return {
    checkedAt: raw.checkedAt,
    issues: raw.issues.map((issue) => ({
      ...issue,
      severity: issue.severity.toLowerCase() as IssueSeverity,
    })),
  };
}

export const DEFAULT_LAG_WARN_THRESHOLD = 10_000;
