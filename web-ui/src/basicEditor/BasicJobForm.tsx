import { useEffect, useRef, useState } from "react";
import type { ChangeEvent, KeyboardEvent } from "react";
import type {
  DesiredJobState,
  JobDefinition,
  JobType,
  RandomSamplerConfig,
  RouteAppConfig,
} from "../types";
import { isErrorOnField, isErrorUnderField } from "./errorFieldMap";
import { RandomSamplerConfigForm } from "./RandomSamplerConfigForm";
import { RouteAppConfigForm } from "./RouteAppConfigForm";
import { StringMapEditor } from "./StringMapEditor";

const JOB_TYPE_OPTIONS: JobType[] = ["ROUTE_APP", "RANDOM_SAMPLER"];
const DESIRED_STATE_OPTIONS: DesiredJobState[] = ["ACTIVE", "PAUSED"];

export interface BasicJobFormProps {
  def: JobDefinition | null;
  jsonParseError: string | null;
  onChange: (next: JobDefinition) => void;
  /**
   * Already-normalized server-validation error path (see
   * {@link ./errorFieldMap#normalizeErrorPath}). When set, the matching Basic
   * field is highlighted and scrolled/focused into view.
   */
  errorPath?: string | null;
}

/**
 * Basic tab form for editing top-level JobDefinition fields plus the per-jobType
 * config panel. Two-way bound to the caller's parsed def; every edit calls
 * `onChange` with a fresh object.
 */
export function BasicJobForm({
  def,
  jsonParseError,
  onChange,
  errorPath,
}: BasicJobFormProps) {
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!errorPath || !rootRef.current) return;
    // Find the deepest field marker whose `data-error-path` is at or above the
    // error path. That's the most specific Basic-form field we can highlight,
    // and the one worth scrolling into view / focusing.
    const candidates = rootRef.current.querySelectorAll<HTMLElement>(
      "[data-error-path]",
    );
    let best: HTMLElement | null = null;
    let bestLen = -1;
    candidates.forEach((el) => {
      const p = el.dataset.errorPath;
      if (!p) return;
      const matches =
        errorPath === p ||
        errorPath.startsWith(`${p}.`) ||
        errorPath.startsWith(`${p}[`);
      if (matches && p.length > bestLen) {
        best = el;
        bestLen = p.length;
      }
    });
    if (!best) return;
    const target = best as HTMLElement;
    target.scrollIntoView({ behavior: "smooth", block: "nearest" });
    const focusable =
      target.matches("input, select, textarea, button")
        ? (target as HTMLElement)
        : target.querySelector<HTMLElement>(
            "input:not([type=hidden]), select, textarea, button",
          );
    focusable?.focus({ preventScroll: true });
  }, [errorPath]);

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

  const onJobTypeChange = (nextType: JobType) => {
    if (nextType === def.jobType) return;
    onChange(applyJobTypeSwitch(def, nextType));
  };

  return (
    <div className="form-stack form-stack--wide" ref={rootRef}>
      <div className="form-row">
        <label className="form-field">
          <span className="form-label">Job id</span>
          <input
            className="text-input"
            type="text"
            autoComplete="off"
            spellCheck={false}
            value={def.jobId}
            onChange={(e) => update("jobId", e.currentTarget.value)}
            aria-invalid={isErrorOnField(errorPath, "jobId") || undefined}
            data-error-path="jobId"
          />
        </label>

        <label className="form-field">
          <span className="form-label">Job type</span>
          <select
            className="select-inline form-select"
            value={def.jobType}
            onChange={(e) => onJobTypeChange(e.currentTarget.value as JobType)}
            aria-invalid={isErrorOnField(errorPath, "jobType") || undefined}
            data-error-path="jobType"
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
      </div>

      <div className="form-row">
        <label className="form-field">
          <span className="form-label">Desired state</span>
          <select
            className="select-inline form-select"
            value={def.desiredState}
            onChange={(e) =>
              update("desiredState", e.currentTarget.value as DesiredJobState)
            }
            aria-invalid={isErrorOnField(errorPath, "desiredState") || undefined}
            data-error-path="desiredState"
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
            aria-invalid={isErrorOnField(errorPath, "siteAffinity") || undefined}
            data-error-path="siteAffinity"
          />
        </label>
      </div>

      <div className="form-row">
        <label className="form-field">
          <span className="form-label">Priority</span>
          <input
            className="text-input"
            type="number"
            value={Number.isFinite(def.priority) ? def.priority : 0}
            onChange={onNumberChange("priority")}
            aria-invalid={isErrorOnField(errorPath, "priority") || undefined}
            data-error-path="priority"
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
            aria-invalid={isErrorOnField(errorPath, "parallelism") || undefined}
            data-error-path="parallelism"
          />
        </label>
      </div>

      <div className="form-field">
        <span className="form-label">Labels</span>
        <StringMapEditor
          value={def.labels ?? {}}
          onChange={(next) => update("labels", next)}
          addLabel="+ Add label"
          emptyLabel="No labels."
          invalid={isErrorUnderField(errorPath, "labels")}
          errorScope="labels"
        />
      </div>

      <div className="form-field">
        <span className="form-label">Tags</span>
        <TagsEditor
          value={def.tags ?? []}
          onChange={(next) => update("tags", next)}
          invalid={isErrorUnderField(errorPath, "tags")}
          errorScope="tags"
        />
      </div>

      {def.jobType === "ROUTE_APP" ? (
        <RouteAppConfigForm
          value={def.routeAppConfig ?? defaultRouteAppConfig()}
          onChange={(next) => update("routeAppConfig", next)}
          errorPath={errorPath}
        />
      ) : null}

      {def.jobType === "RANDOM_SAMPLER" ? (
        <RandomSamplerConfigForm
          value={def.randomSamplerConfig ?? defaultRandomSamplerConfig()}
          onChange={(next) => update("randomSamplerConfig", next)}
          errorPath={errorPath}
        />
      ) : null}

      {unsupportedJobType ? (
        <p className="muted" style={{ marginTop: "0.5rem" }}>
          Job type <code className="mono">{def.jobType}</code> is not supported in the
          Basic editor. Use the Advanced tab.
        </p>
      ) : null}
    </div>
  );
}

// ---- JobType switch --------------------------------------------------------

export function defaultRouteAppConfig(): RouteAppConfig {
  return {
    inputTopic: "",
    inputFormat: "JSON",
    outputFormat: "JSON",
    protobufSchemaSubject: null,
    routes: [],
    streamProperties: {},
    serdeProperties: {},
  };
}

export function defaultRandomSamplerConfig(): RandomSamplerConfig {
  return {
    inputTopic: "",
    outputTopic: "",
    rate: 0.1,
    streamProperties: {},
  };
}

/**
 * Rewrite the def so exactly one of `routeAppConfig` / `randomSamplerConfig` is
 * populated to match the new jobType. Preserves the existing config for the target
 * type if one is already present so switching back and forth is non-destructive.
 */
export function applyJobTypeSwitch(def: JobDefinition, nextType: JobType): JobDefinition {
  if (nextType === "ROUTE_APP") {
    return {
      ...def,
      jobType: "ROUTE_APP",
      routeAppConfig: def.routeAppConfig ?? defaultRouteAppConfig(),
      randomSamplerConfig: null,
    };
  }
  if (nextType === "RANDOM_SAMPLER") {
    return {
      ...def,
      jobType: "RANDOM_SAMPLER",
      randomSamplerConfig: def.randomSamplerConfig ?? defaultRandomSamplerConfig(),
      routeAppConfig: null,
    };
  }
  return { ...def, jobType: nextType };
}

// ---- Tags editor -----------------------------------------------------------

function TagsEditor({
  value,
  onChange,
  invalid,
  errorScope,
}: {
  value: string[];
  onChange: (next: string[]) => void;
  invalid?: boolean;
  errorScope?: string;
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
    <div
      className={invalid ? "chip-editor chip-editor-invalid" : "chip-editor"}
      aria-invalid={invalid || undefined}
      data-error-path={errorScope}
    >
      <div className="chip-row">
        {value.map((tag) => (
          <span key={tag} className="chip">
            <span className="chip-label">{tag}</span>
            <button
              type="button"
              className="chip-remove danger"
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
