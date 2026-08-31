import { useState } from "react";
import type { ChangeEvent, KeyboardEvent } from "react";
import type {
  DesiredJobState,
  JobDefinition,
  JobType,
  RandomSamplerConfig,
  RouteAppConfig,
} from "../types";
import { RandomSamplerConfigForm } from "./RandomSamplerConfigForm";
import { RouteAppConfigForm } from "./RouteAppConfigForm";
import { StringMapEditor } from "./StringMapEditor";

const JOB_TYPE_OPTIONS: JobType[] = ["ROUTE_APP", "RANDOM_SAMPLER"];
const DESIRED_STATE_OPTIONS: DesiredJobState[] = ["ACTIVE", "PAUSED"];

export interface BasicJobFormProps {
  def: JobDefinition | null;
  jsonParseError: string | null;
  onChange: (next: JobDefinition) => void;
}

/**
 * Basic tab form for editing top-level JobDefinition fields plus the per-jobType
 * config panel. Two-way bound to the caller's parsed def; every edit calls
 * `onChange` with a fresh object.
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

  const onJobTypeChange = (nextType: JobType) => {
    if (nextType === def.jobType) return;
    onChange(applyJobTypeSwitch(def, nextType));
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
          onChange={(e) => onJobTypeChange(e.currentTarget.value as JobType)}
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
        <StringMapEditor
          value={def.labels ?? {}}
          onChange={(next) => update("labels", next)}
          addLabel="+ Add label"
          emptyLabel="No labels."
        />
      </div>

      <div className="form-field">
        <span className="form-label">Tags</span>
        <TagsEditor
          value={def.tags ?? []}
          onChange={(next) => update("tags", next)}
        />
      </div>

      {def.jobType === "ROUTE_APP" ? (
        <RouteAppConfigForm
          value={def.routeAppConfig ?? defaultRouteAppConfig()}
          onChange={(next) => update("routeAppConfig", next)}
        />
      ) : null}

      {def.jobType === "RANDOM_SAMPLER" ? (
        <RandomSamplerConfigForm
          value={def.randomSamplerConfig ?? defaultRandomSamplerConfig()}
          onChange={(next) => update("randomSamplerConfig", next)}
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
