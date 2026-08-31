import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ChangeEvent, KeyboardEvent } from "react";
import type { DesiredJobState, JobDefinition, JobType } from "../types";

const JOB_TYPE_OPTIONS: JobType[] = ["ROUTE_APP", "RANDOM_SAMPLER"];
const DESIRED_STATE_OPTIONS: DesiredJobState[] = ["ACTIVE", "PAUSED"];

export interface BasicJobFormProps {
  def: JobDefinition | null;
  jsonParseError: string | null;
  onChange: (next: JobDefinition) => void;
}

/**
 * Basic tab form for editing top-level JobDefinition fields (scalars, labels, tags).
 * Two-way bound to the caller's parsed def; every edit calls `onChange` with a fresh
 * object. Config panels (ROUTE_APP, RANDOM_SAMPLER) land in a follow-up commit.
 */
export function BasicJobForm({ def, jsonParseError, onChange }: BasicJobFormProps) {
  if (!def) {
    return (
      <p className="muted">
        {jsonParseError
          ? `Basic editor unavailable: ${jsonParseError}. Fix in the Advanced tab.`
          : "Basic editor unavailable. Fix in the Advanced tab."}
      </p>
    );
  }

  const unsupportedJobType =
    def.jobType !== "ROUTE_APP" && def.jobType !== "RANDOM_SAMPLER";

  const update = <K extends keyof JobDefinition>(key: K, value: JobDefinition[K]) => {
    onChange({ ...def, [key]: value });
  };

  const onNumberChange = (key: "priority" | "parallelism") =>
    (e: ChangeEvent<HTMLInputElement>) => {
      const v = e.currentTarget.valueAsNumber;
      if (Number.isFinite(v)) update(key, v);
    };

  return (
    <div className="form-stack">
      <label className="form-field">
        <span className="form-label">Job id</span>
        <input
          className="text-input"
          type="text"
          autoComplete="off"
          spellCheck={false}
          value={def.jobId}
          onChange={(e) => update("jobId", e.currentTarget.value)}
        />
      </label>

      <label className="form-field">
        <span className="form-label">Job type</span>
        <select
          className="select-inline form-select"
          value={def.jobType}
          onChange={(e) => update("jobType", e.currentTarget.value as JobType)}
        >
          {JOB_TYPE_OPTIONS.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
          {unsupportedJobType ? (
            // Keep the current value selectable so the select doesn't silently rewrite
            // an unknown jobType coming from the wire (e.g. ALARMS_TO_ZTR).
            <option value={def.jobType}>{def.jobType}</option>
          ) : null}
        </select>
      </label>

      <label className="form-field">
        <span className="form-label">Desired state</span>
        <select
          className="select-inline form-select"
          value={def.desiredState}
          onChange={(e) =>
            update("desiredState", e.currentTarget.value as DesiredJobState)
          }
        >
          {DESIRED_STATE_OPTIONS.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
          {def.desiredState !== "ACTIVE" && def.desiredState !== "PAUSED" ? (
            // DELETED is a sentinel the API emits; keep it selectable if it arrives so
            // the current value is preserved on save even though we don't offer it.
            <option value={def.desiredState}>{def.desiredState}</option>
          ) : null}
        </select>
      </label>

      <label className="form-field">
        <span className="form-label">Site affinity</span>
        <input
          className="text-input"
          type="text"
          autoComplete="off"
          spellCheck={false}
          placeholder="site-a"
          value={def.siteAffinity}
          onChange={(e) => update("siteAffinity", e.currentTarget.value)}
        />
      </label>

      <label className="form-field">
        <span className="form-label">Priority</span>
        <input
          className="text-input"
          type="number"
          value={Number.isFinite(def.priority) ? def.priority : 0}
          onChange={onNumberChange("priority")}
        />
      </label>

      <label className="form-field">
        <span className="form-label">Parallelism</span>
        <input
          className="text-input"
          type="number"
          min={0}
          value={Number.isFinite(def.parallelism) ? def.parallelism : 0}
          onChange={onNumberChange("parallelism")}
        />
      </label>

      <div className="form-field">
        <span className="form-label">Labels</span>
        <LabelsEditor
          value={def.labels ?? {}}
          onChange={(next) => update("labels", next)}
        />
      </div>

      <div className="form-field">
        <span className="form-label">Tags</span>
        <TagsEditor
          value={def.tags ?? []}
          onChange={(next) => update("tags", next)}
        />
      </div>

      {unsupportedJobType ? (
        <p className="muted" style={{ marginTop: "0.5rem" }}>
          Job type <code className="mono">{def.jobType}</code> is not supported in the
          Basic editor. Use the Advanced tab.
        </p>
      ) : null}
    </div>
  );
}

// ---- Labels editor ---------------------------------------------------------

export interface LabelRow {
  id: string;
  key: string;
  value: string;
}

function nextRowId(): string {
  const c = typeof crypto !== "undefined" ? crypto : undefined;
  return c && "randomUUID" in c ? c.randomUUID() : Math.random().toString(36).slice(2);
}

export function labelsToRows(labels: Record<string, string>): LabelRow[] {
  return Object.entries(labels).map(([k, v]) => ({ id: nextRowId(), key: k, value: v }));
}

export function rowsToLabels(rows: LabelRow[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const row of rows) {
    const key = row.key.trim();
    if (!key) continue;
    // Last write wins; the duplicate-key row shows an error state in the UI so the
    // user can see something is off before saving.
    out[key] = row.value;
  }
  return out;
}

function LabelsEditor({
  value,
  onChange,
}: {
  value: Record<string, string>;
  onChange: (next: Record<string, string>) => void;
}) {
  // Rows are the local editing state so typing in a key input doesn't shuffle row
  // order every keystroke. We only re-derive from `value` when the parent's object
  // has actually changed to something we didn't produce (e.g. a JSON-tab edit).
  const [rows, setRows] = useState<LabelRow[]>(() => labelsToRows(value));
  const lastEmittedRef = useRef<Record<string, string>>(value);

  useEffect(() => {
    if (value === lastEmittedRef.current) return;
    const currentSerialized = rowsToLabels(rows);
    if (shallowEqualRecord(currentSerialized, value)) {
      lastEmittedRef.current = value;
      return;
    }
    setRows(labelsToRows(value));
    lastEmittedRef.current = value;
    // Intentionally exclude `rows` from deps so external updates don't loop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  const commit = useCallback(
    (nextRows: LabelRow[]) => {
      setRows(nextRows);
      const next = rowsToLabels(nextRows);
      lastEmittedRef.current = next;
      onChange(next);
    },
    [onChange],
  );

  const duplicateKeys = useMemo(() => {
    const seen = new Map<string, number>();
    const dupes = new Set<string>();
    for (const r of rows) {
      const k = r.key.trim();
      if (!k) continue;
      const prev = seen.get(k);
      if (prev != null) dupes.add(k);
      else seen.set(k, 1);
    }
    return dupes;
  }, [rows]);

  return (
    <div className="kv-editor">
      {rows.length === 0 ? (
        <p className="muted" style={{ margin: 0, fontSize: "0.85rem" }}>
          No labels.
        </p>
      ) : null}
      {rows.map((row) => {
        const trimmedKey = row.key.trim();
        const dupe = trimmedKey !== "" && duplicateKeys.has(trimmedKey);
        return (
          <div key={row.id} className="kv-row">
            <input
              className={dupe ? "text-input invalid" : "text-input"}
              type="text"
              placeholder="key"
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
              title={dupe ? "Duplicate label key" : undefined}
            />
            <input
              className="text-input"
              type="text"
              placeholder="value"
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
              className="kv-remove"
              aria-label="Remove label"
              onClick={() => commit(rows.filter((r) => r.id !== row.id))}
            >
              ×
            </button>
          </div>
        );
      })}
      <div>
        <button
          type="button"
          onClick={() =>
            commit([...rows, { id: nextRowId(), key: "", value: "" }])
          }
        >
          + Add label
        </button>
      </div>
    </div>
  );
}

function shallowEqualRecord(a: Record<string, string>, b: Record<string, string>): boolean {
  const ak = Object.keys(a);
  const bk = Object.keys(b);
  if (ak.length !== bk.length) return false;
  for (const k of ak) if (a[k] !== b[k]) return false;
  return true;
}

// ---- Tags editor -----------------------------------------------------------

function TagsEditor({
  value,
  onChange,
}: {
  value: string[];
  onChange: (next: string[]) => void;
}) {
  const [draft, setDraft] = useState("");

  const commitDraft = (raw: string) => {
    const tag = raw.trim();
    if (!tag) return;
    if (value.includes(tag)) {
      setDraft("");
      return;
    }
    onChange([...value, tag]);
    setDraft("");
  };

  const removeTag = (tag: string) => {
    onChange(value.filter((t) => t !== tag));
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter" || e.key === ",") {
      e.preventDefault();
      commitDraft(draft);
      return;
    }
    if (e.key === "Backspace" && draft === "" && value.length > 0) {
      e.preventDefault();
      onChange(value.slice(0, -1));
    }
  };

  const onBlur = () => {
    if (draft.trim() !== "") commitDraft(draft);
  };

  return (
    <div className="chip-editor">
      <div className="chip-row">
        {value.map((tag) => (
          <span key={tag} className="chip">
            <span className="chip-label">{tag}</span>
            <button
              type="button"
              className="chip-remove"
              aria-label={`Remove tag ${tag}`}
              onClick={() => removeTag(tag)}
            >
              ×
            </button>
          </span>
        ))}
        <input
          className="chip-input"
          type="text"
          autoComplete="off"
          spellCheck={false}
          value={draft}
          onChange={(e) => setDraft(e.currentTarget.value)}
          onKeyDown={onKeyDown}
          onBlur={onBlur}
          placeholder={value.length === 0 ? "Type a tag and press Enter…" : ""}
        />
      </div>
    </div>
  );
}
