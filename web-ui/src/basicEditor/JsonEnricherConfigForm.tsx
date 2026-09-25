import type { JsonEnricherConfig } from "../types";
import { isErrorOnField, isErrorUnderField } from "./errorFieldMap";
import { StringMapEditor } from "./StringMapEditor";

export interface JsonEnricherConfigFormProps {
  value: JsonEnricherConfig;
  onChange: (next: JsonEnricherConfig) => void;
  errorPath?: string | null;
}

const SCOPE = "jsonEnricherConfig";

/** Basic-tab config panel for `JSON_ENRICHER` jobs. */
export function JsonEnricherConfigForm({
  value,
  onChange,
  errorPath,
}: JsonEnricherConfigFormProps) {
  const update = <K extends keyof JsonEnricherConfig>(
    key: K,
    next: JsonEnricherConfig[K],
  ) => {
    onChange({ ...value, [key]: next });
  };

  return (
    <section className="config-panel" aria-labelledby="json-enricher-config-heading">
      <h3 id="json-enricher-config-heading" className="config-panel-heading">
        JSON_ENRICHER config
      </h3>

      <div className="form-row">
        <label className="form-field">
          <span className="form-label">Input topic</span>
          <input
            className="text-input"
            type="text"
            autoComplete="off"
            spellCheck={false}
            value={value.inputTopic}
            onChange={(e) => update("inputTopic", e.currentTarget.value)}
            aria-invalid={isErrorOnField(errorPath, `${SCOPE}.inputTopic`) || undefined}
            data-error-path={`${SCOPE}.inputTopic`}
          />
        </label>
        <label className="form-field">
          <span className="form-label">Output topic</span>
          <input
            className="text-input"
            type="text"
            autoComplete="off"
            spellCheck={false}
            value={value.outputTopic}
            onChange={(e) => update("outputTopic", e.currentTarget.value)}
            aria-invalid={isErrorOnField(errorPath, `${SCOPE}.outputTopic`) || undefined}
            data-error-path={`${SCOPE}.outputTopic`}
          />
        </label>
      </div>

      <div className="form-row">
        <label className="form-field">
          <span className="form-label">Lookup topic</span>
          <input
            className="text-input"
            type="text"
            autoComplete="off"
            spellCheck={false}
            value={value.lookupTopic}
            onChange={(e) => update("lookupTopic", e.currentTarget.value)}
            aria-invalid={isErrorOnField(errorPath, `${SCOPE}.lookupTopic`) || undefined}
            data-error-path={`${SCOPE}.lookupTopic`}
          />
        </label>
        <label className="form-field">
          <span className="form-label">Source label</span>
          <input
            className="text-input"
            type="text"
            autoComplete="off"
            spellCheck={false}
            value={value.source}
            onChange={(e) => update("source", e.currentTarget.value)}
            aria-invalid={isErrorOnField(errorPath, `${SCOPE}.source`) || undefined}
            data-error-path={`${SCOPE}.source`}
          />
        </label>
        <label className="form-field">
          <span className="form-label">Enrichment name</span>
          <input
            className="text-input"
            type="text"
            autoComplete="off"
            spellCheck={false}
            value={value.enrichmentName}
            onChange={(e) => update("enrichmentName", e.currentTarget.value)}
            aria-invalid={isErrorOnField(errorPath, `${SCOPE}.enrichmentName`) || undefined}
            data-error-path={`${SCOPE}.enrichmentName`}
          />
        </label>
      </div>

      <div className="form-row">
        <label className="form-field">
          <span className="form-label">Join key path</span>
          <input
            className="text-input"
            type="text"
            autoComplete="off"
            spellCheck={false}
            value={value.joinKeyPath}
            onChange={(e) => update("joinKeyPath", e.currentTarget.value)}
            aria-invalid={isErrorOnField(errorPath, `${SCOPE}.joinKeyPath`) || undefined}
            data-error-path={`${SCOPE}.joinKeyPath`}
          />
        </label>
      </div>

      <label className="form-field">
        <span className="form-label">
          Join key CEL
          <span className="form-hint"> (variable `key` is the extracted field)</span>
        </span>
        <textarea
          className="text-input"
          rows={4}
          spellCheck={false}
          value={value.joinKeyCel}
          onChange={(e) => update("joinKeyCel", e.currentTarget.value)}
          aria-invalid={isErrorOnField(errorPath, `${SCOPE}.joinKeyCel`) || undefined}
          data-error-path={`${SCOPE}.joinKeyCel`}
        />
      </label>

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
    </section>
  );
}
