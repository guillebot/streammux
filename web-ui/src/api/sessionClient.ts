import { apiFetch } from "./http";

export interface SessionInfo {
  username: string;
  role: string;
  roles?: string[];
  authType: string;
  avatarUrl?: string | null;
}

/** True when in-app auth is on and the session includes the admin role. */
export function sessionHasAdmin(session: SessionInfo): boolean {
  if (session.authType === "PROXY") return false;
  if (session.role === "admin") return true;
  return (session.roles ?? []).includes("admin");
}

export async function getSession(): Promise<SessionInfo> {
  const authRes = await apiFetch("/api/auth/me");
  if (authRes.ok) {
    const body = (await authRes.json()) as SessionInfo & { roles?: string[] };
    return {
      username: body.username,
      role: body.role ?? body.roles?.[body.roles.length - 1] ?? "viewer",
      roles: body.roles,
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
