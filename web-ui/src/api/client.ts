import type { JobDefinition, JobEvent, JobLease, JobRuntimeStatus } from "../types";

const JOBS = "/jobs";

async function readJson<T>(response: Response): Promise<T | null> {
  const text = await response.text();
  if (!text.trim()) return null;
  return JSON.parse(text) as T;
}

async function handleError(response: Response): Promise<never> {
  let detail = response.statusText;
  try {
    const body = await response.text();
    if (body) {
      try {
        const parsed = JSON.parse(body) as { message?: string; error?: string };
        if (typeof parsed.message === "string" && parsed.message.trim()) detail = parsed.message;
        else if (typeof parsed.error === "string" && parsed.error.trim()) detail = parsed.error;
        else detail = body.length > 200 ? `${body.slice(0, 200)}…` : body;
      } catch {
        detail = body.length > 200 ? `${body.slice(0, 200)}…` : body;
      }
    }
  } catch {
    /* ignore */
  }
  throw new Error(`${response.status} ${detail}`);
}

export async function listJobs(): Promise<JobDefinition[]> {
  const res = await fetch(JOBS);
  if (!res.ok) await handleError(res);
  return (await res.json()) as JobDefinition[];
}

export async function getJob(jobId: string): Promise<JobDefinition> {
  const res = await fetch(`${JOBS}/${encodeURIComponent(jobId)}`);
  if (!res.ok) await handleError(res);
  return (await res.json()) as JobDefinition;
}

export async function createJob(definition: JobDefinition): Promise<JobDefinition> {
  const res = await fetch(JOBS, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(definition),
  });
  if (!res.ok) await handleError(res);
  return (await res.json()) as JobDefinition;
}

export async function updateJob(jobId: string, definition: JobDefinition): Promise<JobDefinition> {
  const res = await fetch(`${JOBS}/${encodeURIComponent(jobId)}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(definition),
  });
  if (!res.ok) await handleError(res);
  return (await res.json()) as JobDefinition;
}

export async function pauseJob(jobId: string): Promise<void> {
  const res = await fetch(`${JOBS}/${encodeURIComponent(jobId)}/pause`, { method: "POST" });
  if (!res.ok) await handleError(res);
}

export async function resumeJob(jobId: string): Promise<void> {
  const res = await fetch(`${JOBS}/${encodeURIComponent(jobId)}/resume`, { method: "POST" });
  if (!res.ok) await handleError(res);
}

export async function restartJob(jobId: string): Promise<void> {
  const res = await fetch(`${JOBS}/${encodeURIComponent(jobId)}/restart`, { method: "POST" });
  if (!res.ok) await handleError(res);
}

export async function deleteJob(jobId: string): Promise<void> {
  const res = await fetch(`${JOBS}/${encodeURIComponent(jobId)}`, { method: "DELETE" });
  if (!res.ok) await handleError(res);
}

export async function getStatus(jobId: string): Promise<JobRuntimeStatus | null> {
  const res = await fetch(`${JOBS}/${encodeURIComponent(jobId)}/status`);
  if (!res.ok) await handleError(res);
  return readJson<JobRuntimeStatus>(res);
}

export async function getLease(jobId: string): Promise<JobLease | null> {
  const res = await fetch(`${JOBS}/${encodeURIComponent(jobId)}/lease`);
  if (!res.ok) await handleError(res);
  return readJson<JobLease>(res);
}

export async function getEvents(jobId: string): Promise<JobEvent[]> {
  const res = await fetch(`${JOBS}/${encodeURIComponent(jobId)}/events`);
  if (!res.ok) await handleError(res);
  return (await res.json()) as JobEvent[];
}
