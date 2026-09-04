# Authentication

Streammux supports **session-first** authentication on `job-management-api`, with **Bearer `stm_` tokens** for MCP/CLI automation.

## Modes

| Flag | Default | Purpose |
| ---- | ------- | ------- |
| `STREAMMUX_AUTH_ENABLED` | `false` | Master switch for Postgres + session security |
| `OIDC_ENABLED` | `false` | Microsoft Entra ID (OIDC) |
| `LOCAL_AUTH_ENABLED` | `true` | Break-glass username/password form login |

When auth is disabled, the legacy HTTP Basic filter (`SecurityConfiguration`) applies and the UI uses proxy headers (`Remote-User`) via `GET /jobs/meta/session`.

## Entra OIDC

Set:

```text
OIDC_CLIENT_ID=…
OIDC_CLIENT_SECRET=…
OIDC_TENANT_ID=…
STREAMMUX_UI_URL=https://streammux.onelab.alticeusa.net
```

Browser flow: UI → `/oauth2/authorization/azure` → Entra → session cookie.

## Roles

| Role | Capabilities |
| ---- | ------------ |
| `viewer` | Read jobs, health, logs |
| `operator` | Create/update jobs, Config Studio submit |
| `admin` | Delete jobs, sync live, MCP admin, actuator |

Entra group → role mapping is configured in `OidcUserService` (default role: `viewer`).

## API endpoints

- `GET /api/auth/me` — current user
- `GET /api/auth/config` — auth mode flags for the UI
- `POST /api/auth/login` — local login (JSON session)
- `POST /api/auth/logout`
- `POST /api/auth/password` — change local password

## Edge routing

- **web-ui** nginx proxies `/api/auth`, `/oauth2`, `/login/oauth2` to job-management-api.
- Traefik **does not** use Authelia for Streammux when in-app auth is enabled.
- **`/mcp`** stays Bearer-only at Traefik (no session).

See [BREAK_GLASS.md](../BREAK_GLASS.md) for emergency access.
