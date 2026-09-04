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

On first successful Entra login the API **registers** the user in Postgres (`users` + `user_roles`). Entra group/role claims are mapped on every login **until** an admin overrides them on the Users page. After an override, the persisted roles win; **Sync from Entra** clears the override so the next login remaps groups.

Session authorities always come from the roles that login resolved (database when overridden, Entra mapping otherwise) — not from raw IdP claims alone.

## Roles

| Role | Capabilities |
| ---- | ------------ |
| `viewer` | Read jobs, health, logs |
| `operator` | Create/update jobs, Config Studio submit |
| `admin` | Delete jobs, sync live, MCP admin, actuator, Users page |

Entra group → role mapping is configured in `OidcUserService` (default role: `viewer`). First-login default is not raised without an explicit security review.

## Users administration

Admins use **Users** (`/#/users`) to:

- List local and Entra-registered accounts (last login from `auth_audit`)
- Create local users, reset local passwords, unlock lockouts, enable/disable, delete
- Override Entra-mapped roles (sets `entra_roles_overridden`; sessions are revoked so the change applies immediately)
- Resume Entra group sync per user

The last enabled `admin` cannot be demoted, disabled, or deleted. You cannot delete or disable your own account.

## API endpoints

- `GET /api/auth/me` — current user
- `GET /api/auth/config` — auth mode flags for the UI
- `POST /api/auth/login` — local login (JSON session)
- `POST /api/auth/logout`
- `POST /api/auth/password` — change local password
- `GET /api/admin/users` — list accounts (admin)
- `POST /api/admin/users` — create local user (admin)
- `PUT /api/admin/users/{username}/roles` — set roles; OIDC users become Entra-overridden
- `POST /api/admin/users/{username}/entra-sync` — resume Entra group mapping on next login
- `PATCH /api/admin/users/{username}` — enable/disable, email
- `POST /api/admin/users/{username}/password` — admin password reset (LOCAL only)
- `POST /api/admin/users/{username}/unlock`
- `POST /api/admin/users/{username}/sessions:revoke`
- `DELETE /api/admin/users/{username}`

Admin mutations require the session cookie and `X-XSRF-TOKEN` (from the `XSRF-TOKEN` cookie).

## Edge routing

- **web-ui** nginx proxies `/api/auth`, `/api/admin`, `/oauth2`, `/login/oauth2` to job-management-api.
- Traefik **does not** use Authelia for Streammux when in-app auth is enabled.
- **`/mcp`** stays Bearer-only at Traefik (no session).

See [BREAK_GLASS.md](../BREAK_GLASS.md) for emergency access.
