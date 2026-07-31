import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { listCatalogEntries, type CatalogListItem } from "./api/catalogClient";

type SortKey = "id" | "title" | "jobId" | "updatedAt";
type SortDir = "asc" | "desc";
type SortState = { key: SortKey; dir: SortDir };

function catalogSortValue(row: CatalogListItem, key: SortKey): string | number | null {
  switch (key) {
    case "id":
      return row.id;
    case "title":
      return row.title || "(untitled)";
    case "jobId":
      return row.jobId;
    case "updatedAt":
      return row.updatedAt;
  }
}

function sortCatalogRows(rows: CatalogListItem[], state: SortState): CatalogListItem[] {
  const dir = state.dir === "asc" ? 1 : -1;
  return [...rows].sort((a, b) => {
    const av = catalogSortValue(a, state.key);
    const bv = catalogSortValue(b, state.key);
    const aN = av == null || av === "";
    const bN = bv == null || bv === "";
    if (aN && bN) return 0;
    if (aN) return 1;
    if (bN) return -1;
    if (typeof av === "number" && typeof bv === "number") return (av - bv) * dir;
    return (
      String(av).localeCompare(String(bv), undefined, { numeric: true, sensitivity: "base" }) * dir
    );
  });
}

function SortableTh({
  sortKey,
  sort,
  onSort,
  descFirst = false,
  children,
}: {
  sortKey: SortKey;
  sort: SortState;
  onSort: (key: SortKey, descFirst?: boolean) => void;
  descFirst?: boolean;
  children: ReactNode;
}) {
  const active = sort.key === sortKey;
  const ariaSort = active ? (sort.dir === "asc" ? "ascending" : "descending") : "none";
  const arrow = active ? (sort.dir === "asc" ? "▲" : "▼") : "↕";
  return (
    <th
      scope="col"
      aria-sort={ariaSort}
      className="th-sortable"
      onClick={() => onSort(sortKey, descFirst)}
    >
      <span className="th-sortable-inner">
        {children}
        <span className={active ? "th-sort-indicator active" : "th-sort-indicator"} aria-hidden="true">
          {arrow}
        </span>
      </span>
    </th>
  );
}

export function CatalogList() {
  const [rows, setRows] = useState<CatalogListItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sort, setSort] = useState<SortState>({ key: "id", dir: "asc" });

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

  const toggleSort = (key: SortKey, descFirst = false) => {
    setSort((s) =>
      s.key === key
        ? { key, dir: s.dir === "asc" ? "desc" : "asc" }
        : { key, dir: descFirst ? "desc" : "asc" },
    );
  };

  const sortedRows = useMemo(() => (rows ? sortCatalogRows(rows, sort) : null), [rows, sort]);

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

      {sortedRows && sortedRows.length === 0 ? <p className="muted">No catalog entries yet.</p> : null}

      {sortedRows && sortedRows.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <SortableTh sortKey="id" sort={sort} onSort={toggleSort}>
                  ID
                </SortableTh>
                <SortableTh sortKey="title" sort={sort} onSort={toggleSort}>
                  Title
                </SortableTh>
                <SortableTh sortKey="jobId" sort={sort} onSort={toggleSort}>
                  Job ID (in JSON)
                </SortableTh>
                <SortableTh sortKey="updatedAt" sort={sort} onSort={toggleSort} descFirst>
                  Updated
                </SortableTh>
              </tr>
            </thead>
            <tbody>
              {sortedRows.map((r) => (
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
