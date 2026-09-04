import { useEffect, useState } from "react";
import { NavLink, Outlet } from "react-router-dom";
import { recordSession } from "./api/activityClient";
import { getSession, type SessionInfo } from "./api/sessionClient";
import { BrandWordmark } from "./components/BrandWordmark";
import { HealthBell } from "./components/HealthBell";
import { ThemeToggle } from "./components/ThemeToggle";
import { UserMenu, VersionAbout } from "./components/ShellChrome";

const FALLBACK_SESSION: SessionInfo = {
  username: "operator",
  role: "admin",
  authType: "PROXY",
};

export function AppLayout() {
  const [session, setSession] = useState<SessionInfo>(FALLBACK_SESSION);

  useEffect(() => {
    void recordSession().catch(() => {
      /* session ping is best-effort */
    });
    void getSession()
      .then(setSession)
      .catch(() => setSession(FALLBACK_SESSION));
  }, []);

  return (
    <div className="layout-root">
      <aside className="sidebar" aria-label="Main navigation">
        <div className="sidebar-header">
          <NavLink className="sidebar-brand-link" to="/" title="streammux home">
            <span className="sidebar-brand-icon" aria-hidden>
              ⎈
            </span>
            <BrandWordmark className="sidebar-brand-wordmark" />
          </NavLink>
        </div>
        <nav className="sidebar-nav">
          <NavLink end className="sidebar-link" to="/">
            Job management
          </NavLink>
          <NavLink className="sidebar-link" to="/catalog">
            Job catalog
          </NavLink>
          <NavLink className="sidebar-link" to="/config-studio">
            Config Studio
          </NavLink>
          <NavLink className="sidebar-link" to="/job/builder">
            Job Builder
          </NavLink>
          <NavLink className="sidebar-link" to="/health">
            Health
          </NavLink>
          <NavLink className="sidebar-link" to="/logs">
            Logs
          </NavLink>
          <NavLink className="sidebar-link" to="/docs">
            Documentation
          </NavLink>
          <NavLink className="sidebar-link" to="/mcp">
            MCP
          </NavLink>
          <NavLink className="sidebar-link" to="/settings">
            Settings
          </NavLink>
        </nav>
      </aside>
      <div className="layout-main">
        <header className="top-bar" aria-label="Application chrome">
          <div className="top-bar-spacer" />
          <div className="top-bar-actions">
            <VersionAbout />
            <ThemeToggle />
            <HealthBell />
            <UserMenu session={session} />
          </div>
        </header>
        <div className="layout-content">
          <Outlet />
        </div>
      </div>
    </div>
  );
}
