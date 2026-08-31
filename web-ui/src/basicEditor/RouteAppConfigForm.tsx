import type { ChangeEvent } from "react";
import type { PayloadFormat, RouteAppConfig, RouteDefinition } from "../types";
import { TopicCombobox } from "../TopicCombobox";
import { isErrorOnField, isErrorUnderField } from "./errorFieldMap";
import { RouteListEditor } from "./RouteListEditor";
import { StringMapEditor } from "./StringMapEditor";
import { useTopicCatalog } from "./useTopicCatalog";

const FORMAT_OPTIONS: PayloadFormat[] = ["JSON", "PROTOBUF"];

export interface RouteAppConfigFormProps {
  value: RouteAppConfig;
  onChange: (next: RouteAppConfig) => void;
  /** Normalized server-validation error path. */
  errorPath?: string | null;
}

const SCOPE = "routeAppConfig";

/**
 * Basic-tab config panel for `ROUTE_APP` jobs. Renders the input topic, format
 * selectors, protobuf schema subject, the routes list, and the stream/serde
 * property maps. The filter expression is a plain textarea for now — the
 * nested-group builder replaces it in a follow-up commit.
 */
export function RouteAppConfigForm({
  value,
  onChange,
  errorPath,
}: RouteAppConfigFormProps) {
  const { inputTopics, outputTopics, loading: topicsLoading } = useTopicCatalog();

  const update = <K extends keyof RouteAppConfig>(key: K, next: RouteAppConfig[K]) => {
    onChange({ ...value, [key]: next });
  };

  const onRoutesChange = (next: RouteDefinition[]) => update("routes", next);
  const protobufInUse =
    value.inputFormat === "PROTOBUF" || value.outputFormat === "PROTOBUF";
  const protobufSchemaSubject = value.protobufSchemaSubject ?? "";

  const onSchemaSubjectChange = (e: ChangeEvent<HTMLInputElement>) => {
    const raw = e.currentTarget.value;
    // Preserve JSON null when the user clears the field so the wire shape stays
    // stable for jobs that never had a schema subject.
    update("protobufSchemaSubject", raw.trim() === "" ? null : raw);
  };

  return (
    <section className="config-panel" aria-labelledby="route-app-config-heading">
      <h3 id="route-app-config-heading" className="config-panel-heading">
        ROUTE_APP config
      </h3>

      <div className="form-row">
        <div className="form-field">
          <span className="form-label">
            Input topic
            {topicsLoading ? <span className="muted"> (loading…)</span> : null}
          </span>
          <TopicCombobox
            id={`${SCOPE}-input-topic`}
            ariaLabel="Input topic"
            value={value.inputTopic}
            onChange={(next) => update("inputTopic", next)}
            options={inputTopics}
            disabled={topicsLoading}
            allowCustom
            placeholder="Type to filter topics…"
            invalid={isErrorOnField(errorPath, `${SCOPE}.inputTopic`)}
            dataErrorPath={`${SCOPE}.inputTopic`}
          />
        </div>

        <label className="form-field">
          <span className="form-label">Input format</span>
          <select
            className="select-inline form-select"
            value={value.inputFormat}
            onChange={(e) =>
              update("inputFormat", e.currentTarget.value as PayloadFormat)
            }
            aria-invalid={
              isErrorOnField(errorPath, `${SCOPE}.inputFormat`) || undefined
            }
            data-error-path={`${SCOPE}.inputFormat`}
          >
            {FORMAT_OPTIONS.map((f) => (
              <option key={f} value={f}>
                {f}
              </option>
            ))}
          </select>
        </label>

        <label className="form-field">
          <span className="form-label">Output format</span>
          <select
            className="select-inline form-select"
            value={value.outputFormat}
            onChange={(e) =>
              update("outputFormat", e.currentTarget.value as PayloadFormat)
            }
            aria-invalid={
              isErrorOnField(errorPath, `${SCOPE}.outputFormat`) || undefined
            }
            data-error-path={`${SCOPE}.outputFormat`}
          >
            {FORMAT_OPTIONS.map((f) => (
              <option key={f} value={f}>
                {f}
              </option>
            ))}
          </select>
        </label>
      </div>

      <label className="form-field">
        <span className="form-label">
          Protobuf schema subject
          {!protobufInUse ? (
            <span className="form-hint"> (only used when a format is PROTOBUF)</span>
          ) : null}
        </span>
        <input
          className="text-input"
          type="text"
          autoComplete="off"
          spellCheck={false}
          placeholder="my-subject-value"
          value={protobufSchemaSubject}
          onChange={onSchemaSubjectChange}
          aria-invalid={
            isErrorOnField(errorPath, `${SCOPE}.protobufSchemaSubject`) || undefined
          }
          data-error-path={`${SCOPE}.protobufSchemaSubject`}
        />
      </label>

      <div className="form-field">
        <span className="form-label">Routes</span>
        <RouteListEditor
          value={value.routes}
          onChange={onRoutesChange}
          errorScope={`${SCOPE}.routes`}
          errorPath={errorPath}
          outputTopicOptions={outputTopics}
          topicsLoading={topicsLoading}
        />
      </div>

      <div className="form-row">
        <div className="form-field">
          <span className="form-label">Stream properties</span>
          <StringMapEditor
            value={value.streamProperties}
            onChange={(next) => update("streamProperties", next)}
            addLabel="+ Add stream property"
            emptyLabel="No stream properties."
            invalid={isErrorUnderField(errorPath, `${SCOPE}.streamProperties`)}
            errorScope={`${SCOPE}.streamProperties`}
          />
        </div>

        <div className="form-field">
          <span className="form-label">Serde properties</span>
          <StringMapEditor
            value={value.serdeProperties}
            onChange={(next) => update("serdeProperties", next)}
            addLabel="+ Add serde property"
            emptyLabel="No serde properties."
            invalid={isErrorUnderField(errorPath, `${SCOPE}.serdeProperties`)}
            errorScope={`${SCOPE}.serdeProperties`}
          />
        </div>
      </div>
    </section>
  );
}
