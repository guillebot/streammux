# Model Context Protocol (MCP)

Streammux exposes an **MCP server** so AI clients (Cursor, Claude Code, etc.) can read embedded documentation, inspect jobs, and manage the control plane without using the web UI or raw curl.

## Endpoint

| Access | URL |
| ------ | --- |
| Production (OneLab) | `https://streammux.onelab.alticeusa.net/mcp` |
| Local Compose | `http://127.0.0.1:8090/mcp` (host-bound; not via web UI port) |
| Web console | **MCP** page (`/#/mcp`) — connection help, tool catalog, token management |

External MCP traffic is routed by Traefik to **kstreams1** only (see [Token storage](#token-storage) below). The web UI MCP page proxies token admin to the same pinned MCP backend (`/mcp-admin/…` through nginx).

Authentication is **Bearer `stm_` tokens** only on `/mcp`. There is no Authelia on the MCP path — the token is the credential.

## Quick start (Cursor)

1. Open the web UI **MCP** page and create a token (or run `stmctl` on the MCP container — see below).
2. Add to `~/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "streammux-onelab": {
      "url": "https://streammux.onelab.alticeusa.net/mcp",
      "headers": {
        "Authorization": "Bearer stm_REPLACE_ME"
      }
    }
  }
}
```

3. In Cursor, call `session`, then `list_docs`, then `list_jobs`.

## Token storage

MCP tokens are **not** stored in Kafka or the job-management-api. They live in a **SQLite file** on the MCP container:

| Setting | Default |
| ------- | ------- |
| `MCP_TOKEN_DB_PATH` | `/data/tokens.db` |
| Compose volume | `mcp_tokens:/data` |

Only a **SHA-256 hash** of each token is persisted. The plaintext `stm_…` value is shown once at creation.

### Multiple backends (kstreams1–4)

Streammux runs on **four kstreams hosts**, but the **MCP service runs only on kstreams1** (`kstreams1.srv.hcvlny.alticeusa.net`). Traefik `/mcp` and every web-ui `/mcp-admin` proxy target that host. Tokens live in SQLite on kstreams1's `mcp_tokens` volume — not replicated to kstreams2–4.

**Do not add a shared database only for MCP tokens.** Streammux has no shared SQL store today; introducing Postgres (or similar) just for PAT metadata would be disproportionate.

**Layout (production):**

1. **Traefik pins `/mcp` to kstreams1:8090** so Cursor always hits the same SQLite file.
2. **Create tokens via the web UI MCP page or `stmctl`** on kstreams1's MCP container (works from any web-ui replica — nginx forwards admin calls to kstreams1).
3. **Keep using `stmctl`** for break-glass/bootstrap on kstreams1.

If MCP must be highly available across all four nodes later, revisit with either (a) a small shared Redis/etcd for token hashes, or (b) API tokens in job-management-api once a shared metadata store exists for other reasons. Until then, **one pinned MCP backend + local SQLite** is the intended design.

## Creating tokens

### Web UI (preferred)

1. Sign in via Authelia.
2. Open **MCP** in the sidebar.
3. Use **Create token** — name plus optional scopes (`mcp`, `docs`, `read`, `write`, `admin`).
4. Copy the `stm_…` value immediately; it cannot be retrieved again.

### CLI (break-glass)

On **kstreams1** (the only host running the MCP container):

```bash
docker exec streammux-mcp-1 /stmctl token create \
  --name admin-bootstrap \
  --scopes mcp,docs,read,write,admin \
  --role ADMIN
```

List or revoke:

```bash
docker exec streammux-mcp-1 /stmctl token list
docker exec streammux-mcp-1 /stmctl token revoke --id 1
```

## Scopes

Every token must include scope **`mcp`**. Additional scopes gate tool tiers:

| Scope | Tools |
| ----- | ----- |
| `docs` | `list_docs`, `get_doc`, `search_docs`, `get_schema`, `get_openapi` |
| `read` | Job and catalog reads, `get_health`, `get_settings`, `list_kafka_topics` |
| `write` | Job lifecycle and catalog mutations (`apply=true`) |
| `admin` | `session`, `token_create`, `token_list`, `token_revoke` |

All successful MCP sessions report **`username: admin`** and **`role: ADMIN`** — Streammux does not have per-user MCP accounts yet.

## Tool catalog

### Documentation (embedded)

| Tool | Description |
| ---- | ----------- |
| `list_docs` | List embedded doc paths and titles |
| `get_doc` | Full Markdown for a path (e.g. `docs/overview.md`) |
| `search_docs` | Full-text search with snippets |
| `get_schema` | OpenAPI component schema (`JobDefinition`, …) |
| `get_openapi` | Full OpenAPI JSON |

Docs are also available as MCP resources under the `streammux://` URI scheme.

### Jobs (proxy → job-management-api)

| Tool | Description |
| ---- | ----------- |
| `list_jobs` | All job definitions |
| `get_job` | One definition by `job_id` |
| `get_job_status` | Runtime status |
| `get_job_lease` | Current lease holder |
| `get_job_events` | Audit events |
| `get_health` | Platform health |
| `get_settings` | Non-secret settings |
| `list_kafka_topics` | Allowlisted broker topics |
| `create_job` | Create definition (`apply=true`) |
| `update_job` | Update definition (`apply=true`) |
| `delete_job` | Mark deleted (`apply=true`) |
| `pause_job` / `resume_job` / `restart_job` | Lifecycle commands (`apply=true`) |

### Catalog (proxy → job-catalog-api)

| Tool | Description |
| ---- | ----------- |
| `list_catalog_entries` | Template list |
| `get_catalog_entry` | One template |
| `create_catalog_entry` / `update_catalog_entry` / `delete_catalog_entry` | CRUD (`apply=true`) |
| `duplicate_catalog_entry` | Clone template (`apply=true`) |
| `push_catalog_entry` | Deploy template to live jobs (`apply=true`) |

### Identity

| Tool | Description |
| ---- | ----------- |
| `session` | Caller identity (`admin`) and scopes |
| `token_create` / `token_list` / `token_revoke` | PAT management (`apply=true` on create/revoke) |

**Mutating tools require `apply=true`.** The MCP server adds audit headers (`X-MCP-Client`, `X-MCP-Tool`, `X-MCP-Request-ID`) on writes.

## Suggested agent workflow

1. `list_docs` → `get_doc(docs/overview.md)` and `get_doc(docs/job-types.md)`
2. `get_schema(JobDefinition)` before creating jobs
3. `list_jobs` / `get_job` / `get_job_status` for live state
4. `list_catalog_entries` for templates; `push_catalog_entry` to deploy

## Related

- [API reference](api.md) — REST surface the MCP proxies for jobs
- [Web console](web-console.md) — browser UI pages
- [Deployment](deployment.md) — `mcp` service and environment variables
