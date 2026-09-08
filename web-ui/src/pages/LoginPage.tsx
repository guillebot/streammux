import { FormEvent, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { BrandWordmark } from "../components/BrandWordmark";
import { BrandIcon } from "../components/BrandIcon";
import { ThemeToggle } from "../components/ThemeToggle";
import { apiFetch } from "../api/http";

interface AuthConfig {
  oidc: { enabled: boolean };
  local: { enabled: boolean };
  deployment?: { label?: string; tone?: string };
}

export function LoginPage() {
  const navigate = useNavigate();
  const [config, setConfig] = useState<AuthConfig | null>(null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [rememberMe, setRememberMe] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    void apiFetch("/api/auth/config")
      .then(async (res) => {
        if (!res.ok) return null;
        return (await res.json()) as AuthConfig;
      })
      .then((cfg) => {
        if (cfg) setConfig(cfg);
      })
      .catch(() => {});
    void apiFetch("/api/auth/me")
      .then((res) => {
        if (res.ok) navigate("/", { replace: true });
      })
      .catch(() => {});
  }, [navigate]);

  async function handleLocalLogin(e: FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const body = new URLSearchParams();
      body.set("username", username);
      body.set("password", password);
      if (rememberMe) body.set("remember-me", "true");
      const res = await apiFetch("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body,
      });
      if (!res.ok) {
        setError("Invalid username or password.");
        return;
      }
      navigate("/", { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }

  const deploymentLabel = config?.deployment?.label?.trim();

  return (
    <div className="login-page">
      <div className="login-top-bar">
        <ThemeToggle />
      </div>
      <div className="login-panel panel">
        <h1 className="login-title login-title-brand">
          <span className="login-brand-icon" aria-hidden>
            <BrandIcon />
          </span>
          <BrandWordmark />
        </h1>
        {deploymentLabel ? <p className="login-env muted">{deploymentLabel}</p> : null}
        <p className="muted">Sign in to manage stream-processing jobs.</p>

        {config?.oidc?.enabled ? (
          <a className="login-entra-btn primary" href="/oauth2/authorization/azure">
            Sign in with Microsoft Entra
          </a>
        ) : null}

        {config?.local?.enabled ? (
          <form className="form-stack login-form" onSubmit={(e) => void handleLocalLogin(e)}>
            <label className="form-field">
              <span className="form-label">Username</span>
              <input
                className="text-input"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                autoComplete="username"
              />
            </label>
            <label className="form-field">
              <span className="form-label">Password</span>
              <input
                className="text-input"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
              />
            </label>
            <label className="filter-inline">
              <input
                type="checkbox"
                checked={rememberMe}
                onChange={(e) => setRememberMe(e.target.checked)}
              />
              <span>Remember me</span>
            </label>
            {error ? <div className="banner error">{error}</div> : null}
            <button className="primary" type="submit" disabled={loading}>
              {loading ? "Signing in…" : "Sign in"}
            </button>
          </form>
        ) : null}

        {!config ? <p className="muted">Loading sign-in options…</p> : null}
      </div>
    </div>
  );
}
