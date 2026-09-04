# Break-glass access (Streammux)

Use when Entra OIDC is misconfigured or unavailable and operators need UI/API access.

## Prerequisites

- `STREAMMUX_AUTH_ENABLED=true`
- `LOCAL_AUTH_ENABLED=true`
- Flyway/Postgres enabled for the API (`STREAMMUX_FLYWAY_ENABLED=true` and a reachable `STREAMMUX_DATABASE_URL`)

## Check

1. Confirm a **LOCAL** user with role `admin` exists on **Users** (`/#/users`) or via `GET /api/admin/users` as an existing admin.
2. Confirm you can retrieve the password from **Delinea** (preferred) or the host file `STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD_FILE` (default `$TMPDIR/streammux-breakglass.credentials`, mode `0600`).
3. Sign in at `/#/login` with **Local sign-in** and verify admin access (Users page visible).
4. Record check timestamp, actor, and outcome in the incident/ticket.

Never print the password into chat, tickets, or logs.

## Create

On first boot with auth enabled and **no** `STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD`:

1. The API generates a CSPRNG password (24 characters) and creates local user `breakglass` (or `STREAMMUX_BOOTSTRAP_ADMIN_USERNAME`) with roles `admin`, `operator`, and `viewer`.
2. The username and password are written **once** to `STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD_FILE`. Startup logs the **file path only**.
3. Copy the credential into Delinea, then **delete the file** from the host.
4. If the file cannot be written, startup **fails closed** (no user is created). Set `STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD` from Delinea, or fix the path, and restart.

If the local break-glass user already exists and the env password is unset, the seeder **does not rotate** the password.

Optional env (vault / Delinea, never commit):

```text
STREAMMUX_BOOTSTRAP_ADMIN_USERNAME=breakglass
STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD=<strong-password>
STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD_FILE=/var/lib/streammux/breakglass.credentials
STREAMMUX_BOOTSTRAP_ADMIN_FALLBACK_USERNAME=breakglass-local
```

If `STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD` **is** set, the seeder creates the user or **resets** that local user's password on every boot. Remove the env var after first login so a restart cannot silently rotate the credential.

If the chosen username already exists as an **OIDC** row, set `STREAMMUX_BOOTSTRAP_ADMIN_FALLBACK_USERNAME` for the local account.

## Renew / rotate

1. Generate a new password (CSPRNG, ≥ 24 characters) and store it in Delinea first.
2. Set `STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD` for one restart **or** use **Users → Reset password** on the break-glass local user (revokes sessions).
3. Confirm local login with the new password.
4. Unset `STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD` from the deployment env after a successful login so later restarts do not keep resetting it.
5. Revoke the previous Delinea secret and confirm the old password no longer works.
6. Record rotation timestamp, actor, reason, and validation evidence.

After any suspected exposure, rotate immediately and treat it as an incident.

## Login

1. Open the Streammux UI `/login` route.
2. Use **Local sign-in** with the bootstrap username/password.
3. Change the password (`POST /api/auth/password` or Users → Reset password) once you are in.

## Roll back to Traefik Authelia (legacy)

If in-app auth must be disabled quickly:

1. Set `STREAMMUX_AUTH_ENABLED=false` and redeploy job-management-api (HTTP Basic + proxy headers resume).
2. Re-enable Authelia middleware on Traefik `streammux-ui-router` and `streammux-jobs-router` (see devops git history).
3. Restore `streammux-jobs-upstream-basic` if Spring Basic is still required at the edge.

## MCP / automation

Bearer `stm_` tokens are unchanged — CLI and MCP do not use the session cookie. Mint tokens via `/mcp-admin` or `stmctl token create`. Break-glass principals should not mint long-lived MCP tokens unless an incident requires it.

## Audit

Local and OIDC logins, bootstrap create/reset, and Users-page mutations are recorded in `auth_audit` when Postgres auth is enabled. Audit rows must not include passwords.
