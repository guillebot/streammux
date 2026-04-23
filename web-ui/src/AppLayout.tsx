import { NavLink, Outlet } from "react-router-dom";

export function AppLayout() {
  return (
    <div className="layout-root">
      <aside className="sidebar" aria-label="Main navigation">
        <div className="sidebar-header">
          <img
            className="sidebar-logo"
            src="/logo.png"
            alt=""
            decoding="async"
          />
        </div>
        <div className="sidebar-brand">Streammux</div>
        <nav className="sidebar-nav">
          <NavLink end className="sidebar-link" to="/">
            Job management
          </NavLink>
          <NavLink className="sidebar-link" to="/catalog">
            Job catalog
          </NavLink>
          <NavLink className="sidebar-link" to="/job/builder">
            Job Builder
          </NavLink>
        </nav>
      </aside>
      <div className="layout-main">
        <Outlet />
      </div>
    </div>
  );
}
