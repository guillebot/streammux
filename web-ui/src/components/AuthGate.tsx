import { useEffect, useState, type ReactNode } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { apiFetch } from "../api/http";

export function AuthGate({ children }: { children: ReactNode }) {
  const location = useLocation();
  const [status, setStatus] = useState<"loading" | "authed" | "anon" | "legacy">("loading");

  useEffect(() => {
    let cancelled = false;
    void apiFetch("/api/auth/me")
      .then((res) => {
        if (cancelled) return;
        if (res.ok) {
          setStatus("authed");
          return;
        }
        if (res.status === 404) {
          setStatus("legacy");
          return;
        }
        setStatus("anon");
      })
      .catch(() => {
        if (!cancelled) setStatus("legacy");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (status === "loading") {
    return <div className="page muted">Loading…</div>;
  }
  if (status === "anon") {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}
