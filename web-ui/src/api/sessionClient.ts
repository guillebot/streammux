import { apiFetch } from "./http";

export interface SessionInfo {
  username: string;
  role: string;
  authType: string;
  avatarUrl?: string | null;
}

export async function getSession(): Promise<SessionInfo> {
  const authRes = await apiFetch("/api/auth/me");
  if (authRes.ok) {
    const body = (await authRes.json()) as SessionInfo & { roles?: string[] };
    return {
      username: body.username,
      role: body.role ?? body.roles?.[body.roles.length - 1] ?? "viewer",
      authType: body.authType,
      avatarUrl: body.avatarUrl,
    };
  }
  const res = await apiFetch("/jobs/meta/session");
  if (!res.ok) {
    throw new Error(`${res.status} failed to load session`);
  }
  return (await res.json()) as SessionInfo;
}
