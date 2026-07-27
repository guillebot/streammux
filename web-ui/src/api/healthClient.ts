export type HealthStatus = "UP" | "DEGRADED" | "DOWN" | string;

export interface ModuleHealth {
  name: string;
  status: HealthStatus;
}

export interface TopicPresence {
  key: string;
  name: string;
  present: boolean;
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
  };
  catalog: {
    entryCount: number;
  };
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
  const res = await fetch("/jobs/meta/health");
  if (!res.ok) await handleError(res);
  return readJson<PlatformHealth>(res);
}

export async function getCatalogHealth(): Promise<CatalogHealth> {
  const res = await fetch("/catalog/health");
  if (!res.ok) await handleError(res);
  return readJson<CatalogHealth>(res);
}

export function worstStatus(...statuses: HealthStatus[]): HealthStatus {
  if (statuses.some((s) => s === "DOWN")) return "DOWN";
  if (statuses.some((s) => s === "DEGRADED")) return "DEGRADED";
  return "UP";
}
