import { apiFetch } from "./http";

export interface PlatformSettings {
  loadedAt: string;
  module: {
    applicationName: string;
  };
  kafka: {
    bootstrapServers: string;
    consumerGroupId: string;
  };
  topics: {
    jobDefinitions: string;
    jobLeases: string;
    jobStatus: string;
    jobEvents: string;
    jobCommands: string;
  };
  validation: {
    allowedInputTopics: string[];
    allowedInputTopicPrefixes: string[];
    allowedOutputTopics: string[];
    allowedOutputTopicPrefixes: string[];
  };
  api: {
    exposedActuatorEndpoints: string[];
    springdocShowActuator: boolean;
  };
}

export interface CatalogSettings {
  module: {
    name: string;
  };
  kafka: {
    bootstrapServers: string;
    topic: string;
    clientId: string;
  };
  jobManagementApiUrl: string;
  topicConfig: {
    createTopic: boolean;
    partitions: number;
    replicationFactor: number;
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

export async function getPlatformSettings(): Promise<PlatformSettings> {
  const res = await apiFetch("/jobs/meta/settings");
  if (!res.ok) await handleError(res);
  return readJson<PlatformSettings>(res);
}

export async function getCatalogSettings(): Promise<CatalogSettings> {
  const res = await apiFetch("/catalog/settings");
  if (!res.ok) await handleError(res);
  return readJson<CatalogSettings>(res);
}

function formatList(values: string[] | undefined): string {
  if (!values?.length) return "—";
  return values.join(", ");
}

export function formatSettingsList(values: string[] | undefined): string {
  return formatList(values);
}
