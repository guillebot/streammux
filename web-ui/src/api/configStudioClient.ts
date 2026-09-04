import { apiFetch } from "./http";

export type ConfigStudioStatus = {
  enabled: boolean;
  ready: boolean;
  configurationIssues: string[];
  allowedEnvironments: string[];
  defaultEnvironment: string;
  gitlabProjectUrl: string;
  gitHeadSha: string;
  lastSyncByEnv: Record<string, { gitSha: string; syncedAt: string | null }>;
};

const DISABLED_STATUS: ConfigStudioStatus = {
  enabled: false,
  ready: false,
  configurationIssues: [
    "Config Studio is not available on this deployment (older API without a status endpoint).",
  ],
  allowedEnvironments: [],
  defaultEnvironment: "",
  gitlabProjectUrl: "",
  gitHeadSha: "",
  lastSyncByEnv: {},
};

export async function getConfigStudioStatus(): Promise<ConfigStudioStatus> {
  const res = await apiFetch("/api/config-studio/status");
  if (res.status === 404) {
    return DISABLED_STATUS;
  }
  if (!res.ok) {
    throw new Error(`Config Studio status failed (${res.status})`);
  }
  const data = (await res.json()) as ConfigStudioStatus;
  return {
    ...data,
    configurationIssues: data.configurationIssues ?? [],
    allowedEnvironments: data.allowedEnvironments ?? [],
    lastSyncByEnv: data.lastSyncByEnv ?? {},
  };
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
