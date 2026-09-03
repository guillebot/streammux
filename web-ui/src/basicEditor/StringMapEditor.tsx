import { useCallback, useEffect, useMemo, useRef, useState } from "react";

export interface KvRow {
  id: string;
  key: string;
  value: string;
}

function nextRowId(): string {
  const c = typeof crypto !== "undefined" ? crypto : undefined;
  return c && "randomUUID" in c ? c.randomUUID() : Math.random().toString(36).slice(2);
}

export function recordToRows(record: Record<string, string>): KvRow[] {
  return Object.entries(record).map(([k, v]) => ({ id: nextRowId(), key: k, value: v }));
}

export function rowsToRecord(rows: KvRow[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const row of rows) {
    const key = row.key.trim();
    if (!key) continue;
    // Last write wins; duplicate rows are surfaced in the UI so the user notices.
    out[key] = row.value;
  }
  return out;
}

function shallowEqualRecord(
  a: Record<string, string>,
  b: Record<string, string>,
): boolean {
  const ak = Object.keys(a);
  const bk = Object.keys(b);
  if (ak.length !== bk.length) return false;
  for (const k of ak) if (a[k] !== b[k]) return false;
  return true;
}

export interface StringMapEditorProps {
  value: Record<string, string>;
  onChange: (next: Record<string, string>) => void;
  addLabel?: string;
  emptyLabel?: string;
  keyPlaceholder?: string;
  valuePlaceholder?: string;
  /** Outer container gets `aria-invalid="true"` and a red frame when true. */
  invalid?: boolean;
  /** Used as the `data-error-path` marker for scroll-to-error targeting. */
  errorScope?: string;
}

/**
 * Controlled Record<string, string> editor rendered as key/value rows. Keeps
 * internal row state so typing in a key input doesn't shuffle rows around, and
 * re-syncs from the parent's `value` when it changes to something we didn't emit.
 */
export function StringMapEditor({
  value,
  onChange,
  addLabel = "+ Add entry",
  emptyLabel = "No entries.",
  keyPlaceholder = "key",
  valuePlaceholder = "value",
  invalid,
  errorScope,
}: StringMapEditorProps) {
  const [rows, setRows] = useState<KvRow[]>(() => recordToRows(value));
  const lastEmittedRef = useRef<Record<string, string>>(value);

  useEffect(() => {
    if (value === lastEmittedRef.current) return;
    const currentSerialized = rowsToRecord(rows);
    if (shallowEqualRecord(currentSerialized, value)) {
      lastEmittedRef.current = value;
      return;
    }
    setRows(recordToRows(value));
    lastEmittedRef.current = value;
    // Intentionally exclude `rows` from deps so external updates don't loop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  const commit = useCallback(
    (nextRows: KvRow[]) => {
      setRows(nextRows);
      const next = rowsToRecord(nextRows);
      lastEmittedRef.current = next;
      onChange(next);
    },
    [onChange],
  );

  const duplicateKeys = useMemo(() => {
    const seen = new Set<string>();
    const dupes = new Set<string>();
    for (const r of rows) {
      const k = r.key.trim();
      if (!k) continue;
      if (seen.has(k)) dupes.add(k);
      else seen.add(k);
    }
    return dupes;
  }, [rows]);

  return (
    <div
      className={invalid ? "kv-editor kv-editor-invalid" : "kv-editor"}
      aria-invalid={invalid || undefined}
      data-error-path={errorScope}
    >
      {rows.length === 0 ? (
        <p className="muted" style={{ margin: 0, fontSize: "0.85rem" }}>
          {emptyLabel}
        </p>
      ) : null}
      {rows.map((row) => {
        const trimmedKey = row.key.trim();
        const dupe = trimmedKey !== "" && duplicateKeys.has(trimmedKey);
        return (
          <div key={row.id} className="kv-row">
            <input
              className={dupe ? "text-input kv-key invalid" : "text-input kv-key"}
              type="text"
              placeholder={keyPlaceholder}
              autoComplete="off"
              spellCheck={false}
              value={row.key}
              onChange={(e) => {
                const next = rows.map((r) =>
                  r.id === row.id ? { ...r, key: e.currentTarget.value } : r,
                );
                commit(next);
              }}
              aria-invalid={dupe || undefined}
              title={dupe ? "Duplicate key" : undefined}
            />
            <div className="kv-value">
              <input
                className="text-input"
                type="text"
                placeholder={valuePlaceholder}
                autoComplete="off"
                spellCheck={false}
                value={row.value}
                onChange={(e) => {
                  const next = rows.map((r) =>
                    r.id === row.id ? { ...r, value: e.currentTarget.value } : r,
                  );
                  commit(next);
                }}
              />
              <button
                type="button"
                className="kv-remove danger"
                aria-label="Remove entry"
                onClick={() => commit(rows.filter((r) => r.id !== row.id))}
              >
                ×
              </button>
            </div>
          </div>
        );
      })}
      <div>
        <button
          type="button"
          onClick={() => commit([...rows, { id: nextRowId(), key: "", value: "" }])}
        >
          {addLabel}
        </button>
      </div>
    </div>
  );
}
