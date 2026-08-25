import type { JobEvent } from "../types";
import { apiFetch } from "./http";

const ACTIVITY = "/activity";

async function handleError(response: Response): Promise<never> {
  let detail = response.statusText;
  try {
    const body = await response.text();
    if (body) detail = body.length > 200 ? `${body.slice(0, 200)}…` : body;
  } catch {
    /* ignore */
  }
  throw new Error(`${response.status} ${detail}`);
}

export async function getCurrentActor(): Promise<string> {
  const res = await apiFetch(`${ACTIVITY}/me`);
  if (!res.ok) await handleError(res);
  const body = (await res.json()) as { actor?: string };
  return body.actor ?? "unknown";
}

export async function recordSession(): Promise<void> {
  const res = await apiFetch(`${ACTIVITY}/session`, { method: "POST" });
  if (!res.ok) await handleError(res);
}

export interface ListActivityParams {
  limit?: number;
  jobId?: string;
  eventTypes?: string[];
  actor?: string;
}

export async function listActivity(params: ListActivityParams = {}): Promise<JobEvent[]> {
  const q = new URLSearchParams();
  if (params.limit != null) q.set("limit", String(params.limit));
  if (params.jobId) q.set("jobId", params.jobId);
  if (params.eventTypes) {
    for (const t of params.eventTypes) {
      if (t) q.append("eventType", t);
    }
  }
  if (params.actor) q.set("actor", params.actor);
  const suffix = q.toString() ? `?${q.toString()}` : "";
  const res = await apiFetch(`${ACTIVITY}${suffix}`);
  if (!res.ok) await handleError(res);
  return (await res.json()) as JobEvent[];
}
