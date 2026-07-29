export type McpTokenRecord = {
  id: number;
  name: string;
  display_prefix: string;
  scopes: string[];
  role: string;
  created_at: string;
  last_used_at?: string;
  revoked_at?: string;
};

export type McpTokenCreateResult = McpTokenRecord & {
  token: string;
  warning: string;
};

export async function listMcpTokens(): Promise<McpTokenRecord[]> {
  const res = await fetch("/mcp-admin/tokens");
  if (!res.ok) {
    throw new Error(await readError(res));
  }
  const data = (await res.json()) as McpTokenRecord[] | null;
  return data ?? [];
}

export async function createMcpToken(name: string, scopes: string[]): Promise<McpTokenCreateResult> {
  const res = await fetch("/mcp-admin/tokens", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name, scopes, role: "ADMIN" }),
  });
  if (!res.ok) {
    throw new Error(await readError(res));
  }
  return res.json() as Promise<McpTokenCreateResult>;
}

export async function revokeMcpToken(id: number): Promise<void> {
  const res = await fetch(`/mcp-admin/tokens/${id}`, { method: "DELETE" });
  if (!res.ok) {
    throw new Error(await readError(res));
  }
}

async function readError(res: Response): Promise<string> {
  const text = (await res.text()).trim();
  return text || `HTTP ${res.status}`;
}

export function mcpPublicUrl(): string {
  if (typeof window === "undefined") {
    return "/mcp";
  }
  return `${window.location.origin}/mcp`;
}
