# Break-glass access (Streammux)

Use when Entra OIDC is misconfigured or unavailable and operators need UI/API access.

## Prerequisites

- `STREAMMUX_AUTH_ENABLED=true`
- `LOCAL_AUTH_ENABLED=true`
- Bootstrap admin credentials set in deployment env (vault):

```text
STREAMMUX_BOOTSTRAP_ADMIN_USERNAME=breakglass
STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD=<strong-password>
```

On first startup with an empty `users` table, the API seeds this account with role `admin`.

## Login

1. Open the Streammux UI `/login` route.
2. Use **Local sign-in** with the bootstrap username/password.
3. Change the password under account settings when OIDC is restored (`POST /api/auth/password`).

## Roll back to Traefik Authelia (legacy)

If in-app auth must be disabled quickly:

1. Set `STREAMMUX_AUTH_ENABLED=false` and redeploy job-management-api (HTTP Basic + proxy headers resume).
2. Re-enable Authelia middleware on Traefik `streammux-ui-router` and `streammux-jobs-router` (see devops git history).
3. Restore `streammux-jobs-upstream-basic` if Spring Basic is still required at the edge.

## MCP / automation

Bearer `stm_` tokens are unchanged — CLI and MCP do not use the session cookie. Mint tokens via `/mcp-admin` or `stmctl token create`.

## Audit

Local and OIDC logins are recorded in `auth_audit` when Postgres auth is enabled.
