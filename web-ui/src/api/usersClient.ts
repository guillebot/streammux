import { apiFetch } from "./http";

export const ALL_ROLES = ["viewer", "operator", "admin"] as const;
export type StreammuxRole = (typeof ALL_ROLES)[number];

export type AdminUser = {
  userId: number;
  username: string;
  email: string | null;
  authType: "LOCAL" | "OIDC";
  enabled: boolean;
  lockedUntil: string | null;
  lastLoginAt: string | null;
  roles: string[];
  entraRolesOverridden: boolean;
};

function userPath(username: string): string {
  return `/api/admin/users/${encodeURIComponent(username)}`;
}

async function readError(res: Response): Promise<string> {
  const text = (await res.text()).trim();
  if (!text) return `HTTP ${res.status}`;
  try {
    const json = JSON.parse(text) as { message?: string; error?: string };
    return json.message || json.error || text;
  } catch {
    return text;
  }
}

async function jsonOrThrow<T>(res: Response): Promise<T> {
  if (!res.ok) {
    throw new Error(await readError(res));
  }
  return res.json() as Promise<T>;
}

export async function listAdminUsers(): Promise<AdminUser[]> {
  const res = await apiFetch("/api/admin/users");
  const data = await jsonOrThrow<AdminUser[] | null>(res);
  return data ?? [];
}

export async function createLocalUser(input: {
  username: string;
  email?: string;
  password: string;
  roles: string[];
}): Promise<AdminUser> {
  const res = await apiFetch("/api/admin/users", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
  return jsonOrThrow<AdminUser>(res);
}

export async function updateUserRoles(username: string, roles: string[]): Promise<AdminUser> {
  const res = await apiFetch(`${userPath(username)}/roles`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ roles }),
  });
  return jsonOrThrow<AdminUser>(res);
}

export async function patchAdminUser(
  username: string,
  body: { enabled?: boolean; email?: string },
): Promise<AdminUser> {
  const res = await apiFetch(userPath(username), {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return jsonOrThrow<AdminUser>(res);
}

export async function resetLocalPassword(username: string, password: string): Promise<void> {
  const res = await apiFetch(`${userPath(username)}/password`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ password }),
  });
  await jsonOrThrow(res);
}

export async function unlockAdminUser(username: string): Promise<AdminUser> {
  const res = await apiFetch(`${userPath(username)}/unlock`, { method: "POST" });
  return jsonOrThrow<AdminUser>(res);
}

export async function revokeUserSessions(username: string): Promise<void> {
  const res = await apiFetch(`${userPath(username)}/sessions:revoke`, { method: "POST" });
  await jsonOrThrow(res);
}

export async function resumeEntraSync(username: string): Promise<AdminUser> {
  const res = await apiFetch(`${userPath(username)}/entra-sync`, { method: "POST" });
  return jsonOrThrow<AdminUser>(res);
}

export async function deleteAdminUser(username: string): Promise<void> {
  const res = await apiFetch(userPath(username), { method: "DELETE" });
  await jsonOrThrow(res);
}
