import { useCallback, useEffect, useRef, useState } from "react";
import type { RouteDefinition } from "../types";
import { TopicCombobox } from "../TopicCombobox";
import { IconTrash } from "../jobActionIcons";
import { isErrorOnField, isErrorUnderField } from "./errorFieldMap";
import { FilterExpressionBuilder } from "./FilterExpressionBuilder";

let rowIdCounter = 0;
function nextRowId(): string {
  const c = typeof crypto !== "undefined" ? crypto : undefined;
  if (c && "randomUUID" in c) return c.randomUUID();
  // Non-secure-context fallback (crypto.randomUUID unavailable). Row ids are
  // React keys, not security material, so no PRNG is needed (avoids CWE-338).
  rowIdCounter += 1;
  return `row-${rowIdCounter}`;
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
  /** Base path for error highlighting, e.g. `routeAppConfig.routes`. */
  errorScope?: string;
  /** Normalized server-validation error path. */
  errorPath?: string | null;
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
  errorScope,
  errorPath,
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
      {rows.map((row, index) => {
        const routeScope = errorScope ? `${errorScope}[${index}]` : undefined;
        const routeCardInvalid =
          routeScope != null && isErrorUnderField(errorPath, routeScope);
        const routeIdPath = routeScope ? `${routeScope}.routeId` : undefined;
        const outputTopicPath = routeScope ? `${routeScope}.outputTopic` : undefined;
        const filterPath = routeScope ? `${routeScope}.filterExpression` : undefined;
        return (
          <div
            key={row.id}
            className={
              routeCardInvalid ? "route-card route-card-invalid" : "route-card"
            }
            aria-invalid={routeCardInvalid || undefined}
            data-error-path={routeScope}
          >
            <div className="form-row">
              <label className="form-field">
                <span className="form-label">Route id</span>
                <input
                  className="text-input"
                  type="text"
                  autoComplete="off"
                  spellCheck={false}
                  value={row.route.routeId}
                  onChange={(e) =>
                    updateRow(row.id, { routeId: e.currentTarget.value })
                  }
                  aria-invalid={isErrorOnField(errorPath, routeIdPath) || undefined}
                  data-error-path={routeIdPath}
                />
              </label>

              <div className="form-field form-row-end">
                <button
                  type="button"
                  className="icon-btn danger"
                  onClick={() => removeRow(row.id)}
                  aria-label={`Remove route ${row.route.routeId || "(unnamed)"}`}
                  title="Remove route"
                >
                  <IconTrash />
                </button>
              </div>
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
                invalid={isErrorOnField(errorPath, outputTopicPath)}
                dataErrorPath={outputTopicPath}
              />
            </div>

            <div className="form-field">
              <span className="form-label">Filter expression</span>
              <FilterExpressionBuilder
                value={row.route.filterExpression}
                onChange={(next) => updateRow(row.id, { filterExpression: next })}
                idPrefix={`filter-${row.id}`}
                invalid={isErrorOnField(errorPath, filterPath)}
                errorScope={filterPath}
              />
            </div>
          </div>
        );
      })}
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
