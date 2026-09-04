import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { getSession, sessionHasAdmin, type SessionInfo } from "../api/sessionClient";
import {
  ALL_ROLES,
  createLocalUser,
  deleteAdminUser,
  listAdminUsers,
  patchAdminUser,
  resetLocalPassword,
  resumeEntraSync,
  revokeUserSessions,
  unlockAdminUser,
  updateUserRoles,
  type AdminUser,
} from "../api/usersClient";
import { formatLastLogin, userStatus } from "../usersDisplay";

const FALLBACK_SESSION: SessionInfo = {
  username: "operator",
  role: "admin",
  authType: "PROXY",
};

export function UsersPage() {
  const [session, setSession] = useState<SessionInfo>(FALLBACK_SESSION);
  const [sessionReady, setSessionReady] = useState(false);
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [passwordUser, setPasswordUser] = useState<string | null>(null);

  const isAdmin = sessionHasAdmin(session);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setUsers(await listAdminUsers());
    } catch (err) {
      setUsers([]);
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void getSession()
      .then(setSession)
      .catch(() => setSession(FALLBACK_SESSION))
      .finally(() => setSessionReady(true));
  }, []);

  useEffect(() => {
    if (!isAdmin) {
      setLoading(false);
      return;
    }
    void load();
  }, [isAdmin, load]);

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return users;
    return users.filter((u) => {
      const hay = `${u.username} ${u.email ?? ""} ${u.authType} ${u.roles.join(" ")}`.toLowerCase();
      return hay.includes(q);
    });
  }, [users, query]);

  async function run(action: () => Promise<unknown>, success?: string) {
    setError(null);
    setNotice(null);
    try {
      await action();
      if (success) setNotice(success);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }

  if (!sessionReady) {
    return <div className="page muted">Loading…</div>;
  }

  if (!isAdmin) {
    return (
      <div className="page">
        <header className="page-header">
          <h1>Users</h1>
          <p className="muted">Local and Entra accounts. Admin role required.</p>
        </header>
        <p className="banner error">You need the admin role to manage users.</p>
      </div>
    );
  }

  return (
    <div className="page page--wide">
      <header className="page-header">
        <div>
          <h1>Users</h1>
          <p className="muted">
            Local accounts and Entra users registered on first login. Override Entra groups per user when you need a
            different role than the IdP mapping.
          </p>
        </div>
        <div className="btn-row" style={{ margin: 0 }}>
          <button type="button" className="primary" onClick={() => setCreateOpen(true)}>
            New local user
          </button>
          <button type="button" onClick={() => void load()} disabled={loading}>
            Refresh
          </button>
        </div>
      </header>

      {error ? <p className="banner error">{error}</p> : null}
      {notice ? <p className="banner success">{notice}</p> : null}

      <div className="users-toolbar">
        <input
          className="text-input"
          type="search"
          placeholder="Search users"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Search users"
        />
      </div>

      <div className="table-wrap">
        <table className="users-table">
          <thead>
            <tr>
              <th>User</th>
              <th>Type</th>
              <th>Status</th>
              <th>Last login</th>
              <th>Roles</th>
              <th>Entra</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan={7} className="muted">
                  Loading…
                </td>
              </tr>
            ) : visible.length === 0 ? (
              <tr>
                <td colSpan={7} className="muted">
                  No users match.
                </td>
              </tr>
            ) : (
              visible.map((user) => {
                const status = userStatus(user.enabled, user.lockedUntil);
                return (
                  <tr key={user.userId} className={user.enabled ? undefined : "users-row-disabled"}>
                    <td>
                      <div className="users-identity">
                        <strong>{user.username}</strong>
                        {user.email && user.email !== user.username ? (
                          <span className="muted">{user.email}</span>
                        ) : null}
                      </div>
                    </td>
                    <td>
                      <span className="health-badge health-badge--neutral">{user.authType}</span>
                    </td>
                    <td>
                      <span
                        className={
                          status === "active"
                            ? "health-badge health-badge--ok"
                            : status === "locked"
                              ? "health-badge health-badge--warn"
                              : "health-badge health-badge--bad"
                        }
                      >
                        {status}
                      </span>
                    </td>
                    <td title={user.lastLoginAt ?? undefined}>{formatLastLogin(user.lastLoginAt)}</td>
                    <td>
                      <div className="users-role-toggles">
                        {ALL_ROLES.map((role) => {
                          const checked = user.roles.includes(role);
                          return (
                            <label key={role}>
                              <input
                                type="checkbox"
                                checked={checked}
                                onChange={() => {
                                  const next = checked
                                    ? user.roles.filter((r) => r !== role)
                                    : [...user.roles, role];
                                  void run(() => updateUserRoles(user.username, next));
                                }}
                              />{" "}
                              {role}
                            </label>
                          );
                        })}
                      </div>
                    </td>
                    <td>
                      {user.authType === "OIDC" ? (
                        user.entraRolesOverridden ? (
                          <div className="users-entra">
                            <span className="health-badge health-badge--warn">overridden</span>
                            <button
                              type="button"
                              onClick={() =>
                                void run(
                                  () => resumeEntraSync(user.username),
                                  "Entra group sync will apply on next login.",
                                )
                              }
                            >
                              Sync from Entra
                            </button>
                          </div>
                        ) : (
                          <span className="muted">syncing</span>
                        )
                      ) : (
                        <span className="muted">—</span>
                      )}
                    </td>
                    <td>
                      <div className="users-actions">
                        <button
                          type="button"
                          onClick={() =>
                            void run(() => patchAdminUser(user.username, { enabled: !user.enabled }))
                          }
                        >
                          {user.enabled ? "Disable" : "Enable"}
                        </button>
                        {user.authType === "LOCAL" ? (
                          <button type="button" onClick={() => setPasswordUser(user.username)}>
                            Reset password
                          </button>
                        ) : null}
                        {status === "locked" ? (
                          <button type="button" onClick={() => void run(() => unlockAdminUser(user.username))}>
                            Unlock
                          </button>
                        ) : null}
                        <button
                          type="button"
                          onClick={() =>
                            void run(() => revokeUserSessions(user.username), "Sessions revoked.")
                          }
                        >
                          Revoke sessions
                        </button>
                        <button
                          type="button"
                          onClick={() => {
                            if (!window.confirm(`Delete user ${user.username}? This cannot be undone.`)) return;
                            void run(() => deleteAdminUser(user.username), "User deleted.");
                          }}
                        >
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {createOpen ? (
        <CreateUserModal
          onClose={() => setCreateOpen(false)}
          onCreated={() => {
            setCreateOpen(false);
            setNotice("Local user created.");
            void load();
          }}
          onError={setError}
        />
      ) : null}

      {passwordUser ? (
        <ResetPasswordModal
          username={passwordUser}
          onClose={() => setPasswordUser(null)}
          onDone={() => {
            setPasswordUser(null);
            setNotice("Password reset. Existing sessions were revoked.");
          }}
          onError={setError}
        />
      ) : null}
    </div>
  );
}

function CreateUserModal({
  onClose,
  onCreated,
  onError,
}: {
  onClose: () => void;
  onCreated: () => void;
  onError: (msg: string) => void;
}) {
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [roles, setRoles] = useState<string[]>(["viewer"]);
  const [saving, setSaving] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setSaving(true);
    try {
      await createLocalUser({
        username: username.trim(),
        email: email.trim() || undefined,
        password,
        roles,
      });
      onCreated();
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="modal-backdrop" role="presentation" onClick={onClose}>
      <form
        className="modal-panel"
        role="dialog"
        aria-labelledby="create-user-title"
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => void onSubmit(e)}
      >
        <h2 id="create-user-title" className="modal-title">
          New local user
        </h2>
        <label className="users-field">
          Username
          <input
            className="text-input"
            required
            minLength={2}
            maxLength={64}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="off"
          />
        </label>
        <label className="users-field">
          Email (optional)
          <input
            className="text-input"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="off"
          />
        </label>
        <label className="users-field">
          Temporary password
          <input
            className="text-input"
            type="password"
            required
            minLength={8}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
          />
        </label>
        <fieldset className="users-field">
          <legend>Roles</legend>
          <div className="users-role-toggles">
            {ALL_ROLES.map((role) => (
              <label key={role}>
                <input
                  type="checkbox"
                  checked={roles.includes(role)}
                  onChange={() => {
                    setRoles((cur) =>
                      cur.includes(role) ? cur.filter((r) => r !== role) : [...cur, role],
                    );
                  }}
                />{" "}
                {role}
              </label>
            ))}
          </div>
        </fieldset>
        <div className="btn-row">
          <button type="submit" className="primary" disabled={saving}>
            {saving ? "Creating…" : "Create"}
          </button>
          <button type="button" onClick={onClose}>
            Cancel
          </button>
        </div>
      </form>
    </div>
  );
}

function ResetPasswordModal({
  username,
  onClose,
  onDone,
  onError,
}: {
  username: string;
  onClose: () => void;
  onDone: () => void;
  onError: (msg: string) => void;
}) {
  const [password, setPassword] = useState("");
  const [saving, setSaving] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setSaving(true);
    try {
      await resetLocalPassword(username, password);
      onDone();
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="modal-backdrop" role="presentation" onClick={onClose}>
      <form
        className="modal-panel"
        role="dialog"
        aria-labelledby="reset-password-title"
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => void onSubmit(e)}
      >
        <h2 id="reset-password-title" className="modal-title">
          Reset password
        </h2>
        <p className="muted">
          Set a new password for <strong>{username}</strong>. All of their sessions will be revoked.
        </p>
        <label className="users-field">
          New password
          <input
            className="text-input"
            type="password"
            required
            minLength={8}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
          />
        </label>
        <div className="btn-row">
          <button type="submit" className="primary" disabled={saving}>
            {saving ? "Saving…" : "Reset password"}
          </button>
          <button type="button" onClick={onClose}>
            Cancel
          </button>
        </div>
      </form>
    </div>
  );
}
