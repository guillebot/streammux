import { useCallback, useEffect, useMemo, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {
  createMcpToken,
  listMcpTokens,
  mcpPublicUrl,
  revokeMcpToken,
  type McpTokenCreateResult,
  type McpTokenRecord,
} from "./api/mcpAdminClient";
import {
  DEFAULT_MCP_SCOPES,
  MCP_DOC_CONTENT,
  MCP_TOOL_GROUPS,
  mcpCursorConfig,
} from "./mcpContent";

function formatWhen(iso: string | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

export function McpPage() {
  const mcpUrl = useMemo(() => mcpPublicUrl(), []);
  const [tokens, setTokens] = useState<McpTokenRecord[]>([]);
  const [tokensError, setTokensError] = useState<string | null>(null);
  const [loadingTokens, setLoadingTokens] = useState(true);
  const [tokenName, setTokenName] = useState("");
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [created, setCreated] = useState<McpTokenCreateResult | null>(null);
  const [revokingId, setRevokingId] = useState<number | null>(null);
  const [showRevoked, setShowRevoked] = useState(false);

  const visibleTokens = useMemo(
    () => (showRevoked ? tokens : tokens.filter((t) => !t.revoked_at)),
    [tokens, showRevoked],
  );

  const loadTokens = useCallback(async () => {
    setLoadingTokens(true);
    setTokensError(null);
    try {
      setTokens(await listMcpTokens());
    } catch (err) {
      setTokens([]);
      setTokensError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoadingTokens(false);
    }
  }, []);

  useEffect(() => {
    void loadTokens();
  }, [loadTokens]);

  const cursorSnippet = useMemo(
    () => mcpCursorConfig(mcpUrl, created?.token ?? "stm_REPLACE_ME"),
    [mcpUrl, created?.token],
  );

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    const name = tokenName.trim();
    if (!name) return;
    setCreating(true);
    setCreateError(null);
    setCreated(null);
    try {
      const result = await createMcpToken(name, DEFAULT_MCP_SCOPES);
      setCreated(result);
      setTokenName("");
      await loadTokens();
    } catch (err) {
      setCreateError(err instanceof Error ? err.message : String(err));
    } finally {
      setCreating(false);
    }
  }

  async function handleRevoke(id: number) {
    if (!window.confirm(`Revoke token #${id}?`)) return;
    setRevokingId(id);
    try {
      await revokeMcpToken(id);
      if (created?.id === id) setCreated(null);
      await loadTokens();
    } catch (err) {
      setTokensError(err instanceof Error ? err.message : String(err));
    } finally {
      setRevokingId(null);
    }
  }

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1 className="page-title">MCP</h1>
          <p className="page-subtitle muted">
            Model Context Protocol server for AI clients. Connect with a Bearer{" "}
            <code>stm_</code> token at{" "}
            <code>{mcpUrl}</code>.
          </p>
        </div>
      </header>

      <section className="panel">
        <h2>Connection</h2>
        <p className="muted">
          External MCP clients use the public URL below (Traefik → kstreams1 MCP). Token
          management on this page proxies to the same kstreams1 MCP admin API regardless of
          which web-ui replica you landed on.
        </p>
        <pre className="pre-block mono">{cursorSnippet}</pre>
      </section>

      <section className="panel">
        <h2>API tokens</h2>
        <p className="muted">
          Tokens are stored in SQLite on kstreams1's MCP container (<code>/data/tokens.db</code>,
          volume <code>mcp_tokens</code>). External MCP and this page both target that single
          backend — see the guide below.
        </p>

        <form className="form-stack" style={{ maxWidth: "28rem" }} onSubmit={(e) => void handleCreate(e)}>
          <label className="form-field">
            <span className="form-label">Token name</span>
            <input
              className="text-input"
              type="text"
              value={tokenName}
              onChange={(e) => setTokenName(e.target.value)}
              placeholder="cursor-laptop"
              disabled={creating}
            />
          </label>
          <div className="btn-row" style={{ marginTop: 0 }}>
            <button className="primary" type="submit" disabled={creating || !tokenName.trim()}>
              {creating ? "Creating…" : "Create token"}
            </button>
          </div>
        </form>

        {createError ? <div className="banner error">{createError}</div> : null}

        {created ? (
          <div className="mcp-token-created panel" style={{ marginTop: "1rem" }}>
            <p>
              <strong>Copy this token now</strong> — it will not be shown again.
            </p>
            <pre className="pre-block mono">{created.token}</pre>
            <p className="muted">{created.warning}</p>
          </div>
        ) : null}

        {tokensError ? <div className="banner error">{tokensError}</div> : null}

        <label className="filter-inline" style={{ marginTop: "0.75rem" }}>
          <input
            type="checkbox"
            checked={showRevoked}
            onChange={(e) => setShowRevoked(e.target.checked)}
          />
          <span>Show revoked tokens</span>
        </label>

        {loadingTokens ? (
          <p className="muted">Loading tokens…</p>
        ) : (
          <div className="table-wrap" style={{ marginTop: "0.75rem" }}>
            <table className="mcp-tokens-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Prefix</th>
                <th>Name</th>
                <th>Scopes</th>
                <th>Created</th>
                <th>Last used</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {visibleTokens.length === 0 ? (
                <tr>
                  <td colSpan={8} className="muted">
                    No tokens yet.
                  </td>
                </tr>
              ) : (
                visibleTokens.map((t) => (
                  <tr key={t.id}>
                    <td>{t.id}</td>
                    <td className="mono mcp-tokens-mono">{t.display_prefix}</td>
                    <td>{t.name}</td>
                    <td className="mono mcp-tokens-scopes">{t.scopes.join(", ")}</td>
                    <td>{formatWhen(t.created_at)}</td>
                    <td>{formatWhen(t.last_used_at)}</td>
                    <td>{t.revoked_at ? "Revoked" : "Active"}</td>
                    <td>
                      {!t.revoked_at ? (
                        <button
                          type="button"
                          disabled={revokingId === t.id}
                          onClick={() => void handleRevoke(t.id)}
                        >
                          {revokingId === t.id ? "…" : "Revoke"}
                        </button>
                      ) : null}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
          </div>
        )}
      </section>

      <section className="panel">
        <h2>Tool catalog</h2>
        <div className="docs-grid">
          {MCP_TOOL_GROUPS.map((g) => (
            <div key={g.group} className="docs-card" style={{ cursor: "default" }}>
              <h3 className="docs-card-title">{g.group}</h3>
              <ul className="mcp-tool-list">
                {g.tools.map((t) => (
                  <li key={t.name}>
                    <code>{t.name}</code>
                    <span className="muted"> — {t.desc}</span>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      </section>

      <article className="docs-article markdown-body panel">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{MCP_DOC_CONTENT}</ReactMarkdown>
      </article>
    </div>
  );
}
