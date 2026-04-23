import type { JobDefinition } from "../types";

const BASE = "/catalog";

export interface CatalogListItem {
  id: number;
  title: string;
  jobId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CatalogEntry {
  id: number;
  title: string;
  payload: JobDefinition;
  createdAt: string;
  updatedAt: string;
}

async function handleError(response: Response): Promise<never> {
  const text = await response.text();
  let detail = response.statusText;
  if (text) {
    try {
      const body = JSON.parse(text) as { error?: string };
      if (typeof body.error === "string") detail = body.error;
      else detail = text.length > 200 ? `${text.slice(0, 200)}…` : text;
    } catch {
      detail = text.length > 200 ? `${text.slice(0, 200)}…` : text;
    }
  }
  throw new Error(`${response.status} ${detail}`);
}

export async function listCatalogEntries(): Promise<CatalogListItem[]> {
  const res = await fetch(`${BASE}/entries`);
  if (!res.ok) await handleError(res);
  return (await res.json()) as CatalogListItem[];
}

export async function getCatalogEntry(id: number): Promise<CatalogEntry> {
  const res = await fetch(`${BASE}/entries/${id}`);
  if (!res.ok) await handleError(res);
  return (await res.json()) as CatalogEntry;
}

export async function createCatalogEntry(title: string, payload: JobDefinition): Promise<CatalogEntry> {
  const res = await fetch(`${BASE}/entries`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ title, payload }),
  });
  if (!res.ok) await handleError(res);
  return (await res.json()) as CatalogEntry;
}

export async function updateCatalogEntry(
  id: number,
  title: string,
  payload: JobDefinition,
): Promise<CatalogEntry> {
  const res = await fetch(`${BASE}/entries/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ title, payload }),
  });
  if (!res.ok) await handleError(res);
  return (await res.json()) as CatalogEntry;
}

export async function deleteCatalogEntry(id: number): Promise<void> {
  const res = await fetch(`${BASE}/entries/${id}`, { method: "DELETE" });
  if (!res.ok) await handleError(res);
}

export async function duplicateCatalogEntry(id: number): Promise<CatalogEntry> {
  const res = await fetch(`${BASE}/entries/${id}/duplicate`, { method: "POST" });
  if (!res.ok) await handleError(res);
  return (await res.json()) as CatalogEntry;
}

export async function pushCatalogEntry(id: number): Promise<{ job: unknown }> {
  const res = await fetch(`${BASE}/entries/${id}/push`, { method: "POST" });
  if (!res.ok) await handleError(res);
  return (await res.json()) as { job: unknown };
}
