import type { ChangeEvent } from "react";
import type { RandomSamplerConfig } from "../types";
import { isErrorOnField, isErrorUnderField } from "./errorFieldMap";
import { StringMapEditor } from "./StringMapEditor";

export interface RandomSamplerConfigFormProps {
  value: RandomSamplerConfig;
  onChange: (next: RandomSamplerConfig) => void;
  /** Normalized server-validation error path. */
  errorPath?: string | null;
}

const SCOPE = "randomSamplerConfig";

/** Basic-tab config panel for `RANDOM_SAMPLER` jobs. */
export function RandomSamplerConfigForm({
  value,
  onChange,
  errorPath,
}: RandomSamplerConfigFormProps) {
  const update = <K extends keyof RandomSamplerConfig>(
    key: K,
    next: RandomSamplerConfig[K],
  ) => {
    onChange({ ...value, [key]: next });
  };

  const onRateChange = (e: ChangeEvent<HTMLInputElement>) => {
    const raw = e.currentTarget.valueAsNumber;
    if (Number.isFinite(raw)) update("rate", raw);
  };

  return (
    <section className="config-panel" aria-labelledby="random-sampler-config-heading">
      <h3 id="random-sampler-config-heading" className="config-panel-heading">
        RANDOM_SAMPLER config
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
            aria-invalid={
              isErrorOnField(errorPath, `${SCOPE}.inputTopic`) || undefined
            }
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
            aria-invalid={
              isErrorOnField(errorPath, `${SCOPE}.outputTopic`) || undefined
            }
            data-error-path={`${SCOPE}.outputTopic`}
          />
        </label>

        <label className="form-field">
          <span className="form-label">
            Sample rate
            <span className="form-hint"> (0.0 – 1.0)</span>
          </span>
          <input
            className="text-input"
            type="number"
            min={0}
            max={1}
            step={0.01}
            value={Number.isFinite(value.rate) ? value.rate : 0}
            onChange={onRateChange}
            aria-invalid={isErrorOnField(errorPath, `${SCOPE}.rate`) || undefined}
            data-error-path={`${SCOPE}.rate`}
          />
        </label>
      </div>

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
