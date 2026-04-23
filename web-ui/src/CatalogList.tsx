import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { listCatalogEntries, type CatalogListItem } from "./api/catalogClient";

export function CatalogList() {
  const [rows, setRows] = useState<CatalogListItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setRows(await listCatalogEntries());
    } catch (e) {
      setRows(null);
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1 className="page-title">Job catalog</h1>
          <p className="page-subtitle muted">Stored job definitions. Push deploys to job-management-api.</p>
        </div>
      </header>

      <div className="btn-row">
        <button type="button" onClick={() => void load()}>
          Refresh
        </button>
        <Link className="button-link primary" to="/catalog/items/new">
          New catalog entry
        </Link>
      </div>

      {error ? <div className="banner error">{error}</div> : null}

      {rows && rows.length === 0 ? <p className="muted">No catalog entries yet.</p> : null}

      {rows && rows.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>Title</th>
                <th>Job ID (in JSON)</th>
                <th>Updated</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td className="mono">{r.id}</td>
                  <td>
                    <Link to={`/catalog/items/${r.id}`}>{r.title || "(untitled)"}</Link>
                  </td>
                  <td className="mono">{r.jobId ?? "—"}</td>
                  <td className="mono">{r.updatedAt}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {rows === null && !error ? <p className="muted">Loading…</p> : null}
    </div>
  );
}
