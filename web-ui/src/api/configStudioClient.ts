import { apiFetch } from "./http";

export type ConfigStudioStatus = {
  enabled: boolean;
  ready: boolean;
  allowedEnvironments: string[];
  defaultEnvironment: string;
  gitlabProjectUrl: string;
  gitHeadSha: string;
  lastSyncByEnv: Record<string, { gitSha: string; syncedAt: string | null }>;
};

export async function getConfigStudioStatus(): Promise<ConfigStudioStatus> {
  const res = await apiFetch("/api/config-studio/status");
  if (!res.ok) {
    throw new Error(`Config Studio status failed (${res.status})`);
  }
  return res.json() as Promise<ConfigStudioStatus>;
}

export async function validateConfigStudio(environment: string, ref?: string) {
  const res = await apiFetch("/api/config-studio/validate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ environment, ref: ref ?? "" }),
  });
  if (!res.ok) {
    throw new Error(`Validate failed (${res.status})`);
  }
  return res.json() as Promise<{ environment: string; ref: string; valid: boolean; issues: string[] }>;
}

export async function syncConfigStudio(environment: string, dryRun: boolean, ref?: string) {
  const res = await apiFetch(`/api/config-studio/sync?dryRun=${dryRun ? "true" : "false"}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ environment, ref: ref ?? "" }),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Sync failed (${res.status})`);
  }
  return res.json() as Promise<{
    dryRun: boolean;
    environment: string;
    ref: string;
    applied: string[];
    deleted: string[];
  }>;
}

export async function submitConfigStudio(environment: string, message?: string) {
  const res = await apiFetch("/api/config-studio/submit", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ environment, message: message ?? "" }),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Submit failed (${res.status})`);
  }
  return res.json() as Promise<{ branch: string; mergeRequestIid: number; mergeRequestUrl: string }>;
}
