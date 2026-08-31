import { useCallback, useEffect, useRef, useState } from "react";
import type { RouteDefinition } from "../types";
import { TopicCombobox } from "../TopicCombobox";

function nextRowId(): string {
  const c = typeof crypto !== "undefined" ? crypto : undefined;
  return c && "randomUUID" in c ? c.randomUUID() : Math.random().toString(36).slice(2);
}

interface RouteRow {
  id: string;
  route: RouteDefinition;
}

function toRows(routes: RouteDefinition[]): RouteRow[] {
  return routes.map((r) => ({ id: nextRowId(), route: r }));
}

function fromRows(rows: RouteRow[]): RouteDefinition[] {
  return rows.map((r) => r.route);
}

export interface RouteListEditorProps {
  value: RouteDefinition[];
  onChange: (next: RouteDefinition[]) => void;
  /** Output topic options for the per-route topic combobox. */
  outputTopicOptions?: string[];
  /** Disables the topic comboboxes while the catalog is loading. */
  topicsLoading?: boolean;
}

/**
 * Add / edit / remove routes for a ROUTE_APP job. Each row shows a routeId, an
 * output topic, and a filter expression (plain textarea for now — the nested-group
 * filter builder plugs into the same slot in a follow-up commit).
 */
export function RouteListEditor({
  value,
  onChange,
  outputTopicOptions = [],
  topicsLoading,
}: RouteListEditorProps) {
  // Local row state so edits on one field don't shuffle rows on every keystroke.
  const [rows, setRows] = useState<RouteRow[]>(() => toRows(value));
  const lastEmittedRef = useRef<RouteDefinition[]>(value);

  useEffect(() => {
    if (value === lastEmittedRef.current) return;
    // Cheap identity check: if the parent handed us the same list we last emitted
    // (e.g. after our own onChange), skip the re-derive to preserve row identity.
    const currentSerialized = fromRows(rows);
    if (arraysShallowEqual(currentSerialized, value)) {
      lastEmittedRef.current = value;
      return;
    }
    setRows(toRows(value));
    lastEmittedRef.current = value;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  const commit = useCallback(
    (nextRows: RouteRow[]) => {
      setRows(nextRows);
      const next = fromRows(nextRows);
      lastEmittedRef.current = next;
      onChange(next);
    },
    [onChange],
  );

  const updateRow = (id: string, patch: Partial<RouteDefinition>) => {
    commit(
      rows.map((row) =>
        row.id === id ? { ...row, route: { ...row.route, ...patch } } : row,
      ),
    );
  };

  const removeRow = (id: string) => commit(rows.filter((r) => r.id !== id));

  const addRow = () =>
    commit([
      ...rows,
      {
        id: nextRowId(),
        route: { routeId: "", filterExpression: "", outputTopic: "" },
      },
    ]);

  return (
    <div className="route-list">
      {rows.length === 0 ? (
        <p className="muted" style={{ margin: 0, fontSize: "0.85rem" }}>
          No routes.
        </p>
      ) : null}
      {rows.map((row) => (
        <div key={row.id} className="route-card">
          <div className="route-card-header">
            <label className="form-field route-card-title-field">
              <span className="form-label">Route id</span>
              <input
                className="text-input"
                type="text"
                autoComplete="off"
                spellCheck={false}
                value={row.route.routeId}
                onChange={(e) => updateRow(row.id, { routeId: e.currentTarget.value })}
              />
            </label>
            <button
              type="button"
              className="danger route-card-remove"
              onClick={() => removeRow(row.id)}
              aria-label={`Remove route ${row.route.routeId || "(unnamed)"}`}
            >
              Remove
            </button>
          </div>

          <div className="form-field">
            <span className="form-label">Output topic</span>
            <TopicCombobox
              id={`route-output-topic-${row.id}`}
              ariaLabel="Output topic"
              value={row.route.outputTopic}
              onChange={(next) => updateRow(row.id, { outputTopic: next })}
              options={outputTopicOptions}
              disabled={topicsLoading}
              allowCustom
              placeholder="Type to filter topics…"
            />
          </div>

          <label className="form-field">
            <span className="form-label">Filter expression</span>
            <textarea
              className="text-input mono filter-textarea"
              rows={2}
              spellCheck={false}
              autoComplete="off"
              value={row.route.filterExpression}
              onChange={(e) =>
                updateRow(row.id, { filterExpression: e.currentTarget.value })
              }
              placeholder='e.g. type == "alarm" && severity in ["MAJOR","CRITICAL"]'
            />
          </label>
        </div>
      ))}
      <div>
        <button type="button" onClick={addRow}>
          + Add route
        </button>
      </div>
    </div>
  );
}

function arraysShallowEqual(a: RouteDefinition[], b: RouteDefinition[]): boolean {
  if (a === b) return true;
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    const ai = a[i];
    const bi = b[i];
    if (
      ai.routeId !== bi.routeId ||
      ai.filterExpression !== bi.filterExpression ||
      ai.outputTopic !== bi.outputTopic
    ) {
      return false;
    }
  }
  return true;
}
