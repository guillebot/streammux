import { useEffect, useState } from "react";
import { appVersion } from "../version";
import type { SessionInfo } from "../api/sessionClient";
import { apiFetch } from "../api/http";

function initials(username: string): string {
  const trimmed = username.trim();
  if (!trimmed) return "?";
  const parts = trimmed.split(/[\s@._-]+/).filter(Boolean);
  if (parts.length >= 2) {
    return (parts[0][0] + parts[1][0]).toUpperCase();
  }
  return trimmed.slice(0, 2).toUpperCase();
}

function UserAvatar({ session }: { session: SessionInfo }) {
  const [imgFailed, setImgFailed] = useState(false);
  const avatarUrl = session.avatarUrl?.trim();

  useEffect(() => {
    setImgFailed(false);
  }, [avatarUrl]);

  if (avatarUrl && !imgFailed) {
    return (
      <img
        src={avatarUrl}
        alt=""
        className="user-avatar-img"
        onError={() => setImgFailed(true)}
      />
    );
  }

  return <div className="user-avatar-fallback" aria-hidden>{initials(session.username)}</div>;
}

export function VersionAbout() {
  const [open, setOpen] = useState(false);

  return (
    <>
      <button
        type="button"
        className="shell-chrome-btn shell-chrome-version"
        title={`streammux v${appVersion} — click for details`}
        onClick={() => setOpen(true)}
      >
        v{appVersion}
      </button>
      {open ? (
        <div className="modal-backdrop" role="presentation" onClick={() => setOpen(false)}>
          <div
            className="modal-panel"
            role="dialog"
            aria-labelledby="about-title"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 id="about-title" className="modal-title">
              About <span className="text-accent">streammux</span>
            </h2>
            <p className="muted">Kafka-backed control plane for multi-site stream-processing jobs.</p>
            <dl className="about-dl">
              <dt>Version</dt>
              <dd>v{appVersion}</dd>
            </dl>
            <div className="btn-row">
              <button type="button" onClick={() => setOpen(false)}>
                Close
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}

export function UserMenu({ session }: { session: SessionInfo }) {
  async function signOut() {
    try {
      await apiFetch("/api/auth/logout", { method: "POST" });
    } catch {
      /* ignore */
    }
    window.location.hash = "#/login";
    window.location.reload();
  }

  return (
    <div className="user-menu" title={`Signed in as ${session.username}`}>
      <UserAvatar session={session} />
      <div className="user-menu-text">
        <span className="user-menu-name">{session.username}</span>
        <span className="user-menu-role">{session.role}</span>
      </div>
      <button type="button" className="shell-chrome-btn" onClick={() => void signOut()} title="Sign out">
        Out
      </button>
    </div>
  );
}
