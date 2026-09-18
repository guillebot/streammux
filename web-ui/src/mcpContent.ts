import mcpMd from "../../docs/mcp.md?raw";

export const MCP_DOC_CONTENT = mcpMd;

export type McpToolEntry = { name: string; desc: string };

export type McpToolGroup = { group: string; tools: McpToolEntry[] };

export const MCP_TOOL_GROUPS: McpToolGroup[] = [
  {
    group: "Documentation",
    tools: [
      { name: "list_docs", desc: "List embedded documentation pages" },
      { name: "get_doc", desc: "Read Markdown by path" },
      { name: "search_docs", desc: "Full-text search the docs" },
      { name: "get_schema", desc: "OpenAPI schema (e.g. JobDefinition)" },
      { name: "get_job_schema", desc: "JSON Schema for validation (GET /jobs/schema)" },
      { name: "get_openapi", desc: "Full OpenAPI JSON" },
    ],
  },
  {
    group: "Jobs",
    tools: [
      { name: "list_jobs", desc: "All job definitions" },
      { name: "get_job", desc: "One definition by job_id" },
      { name: "get_job_status", desc: "Runtime status" },
      { name: "get_job_lease", desc: "Current lease" },
      { name: "get_job_events", desc: "Audit events" },
      { name: "list_activity", desc: "Global audit feed" },
      { name: "validate_job", desc: "Dry-run validation (no persist)" },
      { name: "get_health", desc: "Platform health" },
      { name: "get_settings", desc: "Non-secret settings" },
      { name: "list_kafka_topics", desc: "Allowlisted broker topics" },
      { name: "create_job", desc: "Create job (apply=true)" },
      { name: "update_job", desc: "Update job (apply=true)" },
      { name: "rename_job", desc: "Rename job id (apply=true)" },
      { name: "delete_job", desc: "Delete job (apply=true)" },
      { name: "pause_job", desc: "Pause (apply=true)" },
      { name: "resume_job", desc: "Resume (apply=true)" },
      { name: "restart_job", desc: "Restart (apply=true)" },
    ],
  },
  {
    group: "Catalog",
    tools: [
      { name: "list_catalog_entries", desc: "Job templates" },
      { name: "get_catalog_entry", desc: "One template" },
      { name: "get_catalog_health", desc: "Catalog health" },
      { name: "get_catalog_settings", desc: "Catalog settings" },
      { name: "create_catalog_entry", desc: "Create template (apply=true)" },
      { name: "update_catalog_entry", desc: "Update template (apply=true)" },
      { name: "delete_catalog_entry", desc: "Delete template (apply=true)" },
      { name: "duplicate_catalog_entry", desc: "Clone template (apply=true)" },
      { name: "push_catalog_entry", desc: "Deploy template to jobs (apply=true)" },
    ],
  },
  {
    group: "Identity",
    tools: [
      { name: "session", desc: "Caller identity (admin) and scopes" },
      { name: "token_create", desc: "Create stm_ token (apply=true)" },
      { name: "token_list", desc: "Token metadata" },
      { name: "token_revoke", desc: "Revoke token (apply=true)" },
    ],
  },
];

export const DEFAULT_MCP_SCOPES = ["mcp", "docs", "read", "write", "admin"];

export function mcpCursorConfig(mcpUrl: string, token = "stm_REPLACE_ME"): string {
  return JSON.stringify(
    {
      mcpServers: {
        "streammux-onelab": {
          url: mcpUrl,
          headers: { Authorization: `Bearer ${token}` },
        },
      },
    },
    null,
    2,
  );
}

export function isInstallableMcpToken(token: string): boolean {
  const t = token.trim();
  if (!t || t.includes("REPLACE") || t.endsWith("…") || t.endsWith("...")) {
    return false;
  }
  return t.length >= 16;
}

/** Cursor MCP install deeplink — inner transport config only. */
export function cursorMcpInstallUrl(
  serverName: string,
  mcpUrl: string,
  token: string,
): string {
  const configJson = JSON.stringify({
    url: mcpUrl,
    headers: { Authorization: `Bearer ${token}` },
  });
  const config = btoa(configJson);
  return `cursor://anysphere.cursor-deeplink/mcp/install?name=${encodeURIComponent(serverName)}&config=${config}`;
}

export function openCursorMcpInstall(serverName: string, mcpUrl: string, token: string): void {
  window.location.href = cursorMcpInstallUrl(serverName, mcpUrl, token);
}
