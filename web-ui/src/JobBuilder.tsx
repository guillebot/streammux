import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { getKafkaTopicCatalog } from "./api/client";
import {
  JOB_BUILDER_BOOTSTRAP_SERVERS,
  JOB_BUILDER_FALLBACK_INPUT_TOPICS,
  JOB_BUILDER_FALLBACK_OUTPUT_TOPICS,
  JOB_BUILDER_JOB_TYPES,
  buildJobDefinition,
  defaultJobBuilderJsonEnricherOptions,
} from "./jobBuilderOptions";
import type { JobBuilderJsonEnricherOptions } from "./jobBuilderOptions";
import { stashJobDefinitionForNew } from "./jobBuilderStash";
import { newJobTemplate } from "./templates";
import { TopicCombobox } from "./TopicCombobox";

type TopicValidation = { missing: boolean; invalid: boolean };

function validateTopic(topic: string, options: string[], loading: boolean): TopicValidation {
  if (loading) return { missing: false, invalid: false };
  if (topic === "") return { missing: true, invalid: false };
  return { missing: false, invalid: !options.includes(topic) };
}

export function JobBuilder() {
  const navigate = useNavigate();
  const defaults = useMemo(() => newJobTemplate(), []);

  const [jobId, setJobId] = useState(defaults.jobId);
  const [jobType, setJobType] = useState<(typeof JOB_BUILDER_JOB_TYPES)[number]>(JOB_BUILDER_JOB_TYPES[0]);
  const [bootstrapServers, setBootstrapServers] = useState(JOB_BUILDER_BOOTSTRAP_SERVERS[0] ?? "");
  const [inputTopics, setInputTopics] = useState(JOB_BUILDER_FALLBACK_INPUT_TOPICS);
  const [outputTopics, setOutputTopics] = useState(JOB_BUILDER_FALLBACK_OUTPUT_TOPICS);
  const [topicsLoading, setTopicsLoading] = useState(true);
  const [topicsError, setTopicsError] = useState<string | null>(null);
  const [inputTopic, setInputTopic] = useState("");
  const [outputTopic, setOutputTopic] = useState("");
  /** Percent (0–100): API `randomSamplerConfig.rate` = this value ÷ 100 (e.g. 1 → 0.01 ≈ 1 in 100). */
  const [samplePercent, setSamplePercent] = useState(25);
  const [jsonEnricher, setJsonEnricher] = useState<JobBuilderJsonEnricherOptions>(() =>
    defaultJobBuilderJsonEnricherOptions(),
  );
  const updateJsonEnricher = <K extends keyof JobBuilderJsonEnricherOptions>(
    key: K,
    next: JobBuilderJsonEnricherOptions[K],
  ) => setJsonEnricher((prev) => ({ ...prev, [key]: next }));

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setTopicsLoading(true);
      setTopicsError(null);
      try {
        const catalog = await getKafkaTopicCatalog();
        if (cancelled) return;
        const nextInput = catalog.inputTopics.length > 0 ? catalog.inputTopics : JOB_BUILDER_FALLBACK_INPUT_TOPICS;
        const nextOutput =
          catalog.outputTopics.length > 0 ? catalog.outputTopics : JOB_BUILDER_FALLBACK_OUTPUT_TOPICS;
        setInputTopics(nextInput);
        setOutputTopics(nextOutput);
      } catch (e) {
        if (cancelled) return;
        setTopicsError(e instanceof Error ? e.message : String(e));
      } finally {
        if (!cancelled) setTopicsLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const preview = useMemo(
    () =>
      buildJobDefinition({
        jobId,
        jobType,
        bootstrapServers,
        inputTopic,
        outputTopic,
        samplePercent,
        jsonEnricher,
      }),
    [jobId, jobType, bootstrapServers, inputTopic, outputTopic, samplePercent, jsonEnricher],
  );

  const approxOneIn =
    jobType === "RANDOM_SAMPLER" && samplePercent > 0 ? Math.round(100 / samplePercent) : null;

  const onContinue = () => {
    stashJobDefinitionForNew(preview);
    navigate("/job/new");
  };

  const inputV = validateTopic(inputTopic, inputTopics, topicsLoading);
  const outputV = validateTopic(outputTopic, outputTopics, topicsLoading);
  const isEnricher = jobType === "JSON_ENRICHER";
  // The API allowlists the lookup (table) topic as an input topic, so it shares the input list.
  const lookupV = isEnricher
    ? validateTopic(jsonEnricher.lookupTopic, inputTopics, topicsLoading)
    : { missing: false, invalid: false };
  const enricherFieldsMissing =
    isEnricher &&
    (jsonEnricher.source.trim() === "" ||
      jsonEnricher.joinKeyPath.trim() === "" ||
      jsonEnricher.joinKeyCel.trim() === "" ||
      jsonEnricher.enrichmentName.trim() === "");
  const canContinue =
    !topicsLoading &&
    !inputV.missing &&
    !inputV.invalid &&
    !outputV.missing &&
    !outputV.invalid &&
    !lookupV.missing &&
    !lookupV.invalid &&
    !enricherFieldsMissing;
  const continueHint = isEnricher
    ? "Pick input, lookup and output topics from the list and fill in the enricher fields to continue"
    : "Pick both topics from the list to continue";

  return (
    <div className="page">
      <div className="back-row">
        <Link to="/">← Jobs</Link>
      </div>

      <header className="page-header page-header--left" style={{ borderBottom: "none", paddingBottom: 0, marginBottom: "0.5rem" }}>
        <div>
          <h1 className="page-title">Job Builder</h1>
          <p className="page-subtitle muted">
            Pick Kafka connection and topics; opens the JSON editor to review and create. Topic lists come from the
            broker and match the configured allowlists.
          </p>
        </div>
      </header>

      <div className="panel" style={{ marginTop: 0 }}>
        <div className="form-stack">
          <label className="form-field">
            <span className="form-label">Job id</span>
            <input
              className="text-input"
              type="text"
              autoComplete="off"
              spellCheck={false}
              value={jobId}
              onChange={(e) => setJobId(e.target.value)}
            />
          </label>

          <label className="form-field">
            <span className="form-label">Job type</span>
            <select className="select-inline form-select" value={jobType} onChange={(e) => setJobType(e.target.value as (typeof JOB_BUILDER_JOB_TYPES)[number])}>
              {JOB_BUILDER_JOB_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </label>

          <label className="form-field">
            <span className="form-label">Bootstrap servers</span>
            <select
              className="select-inline form-select mono"
              value={bootstrapServers}
              onChange={(e) => setBootstrapServers(e.target.value)}
            >
              {JOB_BUILDER_BOOTSTRAP_SERVERS.map((servers) => (
                <option key={servers} value={servers}>
                  {servers}
                </option>
              ))}
            </select>
          </label>

          <div className="form-field">
            <span className="form-label">
              Input topic
              {topicsLoading ? <span className="muted"> (loading…)</span> : null}
              {!topicsLoading ? <span className="muted"> ({inputTopics.length})</span> : null}
            </span>
            <TopicCombobox
              id="job-builder-input-topic"
              ariaLabel="Input topic"
              value={inputTopic}
              onChange={setInputTopic}
              options={inputTopics}
              disabled={topicsLoading}
              placeholder="Type to filter topics…"
              invalid={inputV.invalid}
            />
            {inputV.invalid ? (
              <span className="form-error" role="alert">
                Select an input topic from the list.
              </span>
            ) : null}
          </div>

          <div className="form-field">
            <span className="form-label">
              Output topic
              {topicsLoading ? <span className="muted"> (loading…)</span> : null}
              {!topicsLoading ? <span className="muted"> ({outputTopics.length})</span> : null}
            </span>
            <TopicCombobox
              id="job-builder-output-topic"
              ariaLabel="Output topic"
              value={outputTopic}
              onChange={setOutputTopic}
              options={outputTopics}
              disabled={topicsLoading}
              placeholder="Type to filter topics…"
              invalid={outputV.invalid}
            />
            {outputV.invalid ? (
              <span className="form-error" role="alert">
                Select an output topic from the list.
              </span>
            ) : null}
          </div>

          {topicsError ? (
            <p className="muted" style={{ margin: 0, fontSize: "0.88rem" }}>
              Could not load topics from the broker ({topicsError}). Showing fallback lists.
            </p>
          ) : null}

          {isEnricher ? (
            <>
              <div className="form-field">
                <span className="form-label">
                  Lookup topic
                  {topicsLoading ? <span className="muted"> (loading…)</span> : null}
                  {!topicsLoading ? <span className="muted"> ({inputTopics.length})</span> : null}
                </span>
                <TopicCombobox
                  id="job-builder-lookup-topic"
                  ariaLabel="Lookup topic"
                  value={jsonEnricher.lookupTopic}
                  onChange={(v) => updateJsonEnricher("lookupTopic", v)}
                  options={inputTopics}
                  disabled={topicsLoading}
                  placeholder="Compacted table topic (keyed by the normalized join key)…"
                  invalid={lookupV.invalid}
                />
                {lookupV.invalid ? (
                  <span className="form-error" role="alert">
                    Select a lookup topic from the list.
                  </span>
                ) : null}
              </div>

              <label className="form-field">
                <span className="form-label">Source label</span>
                <input
                  className="text-input"
                  type="text"
                  autoComplete="off"
                  spellCheck={false}
                  value={jsonEnricher.source}
                  onChange={(e) => updateJsonEnricher("source", e.currentTarget.value)}
                />
              </label>

              <label className="form-field">
                <span className="form-label">Enrichment name</span>
                <input
                  className="text-input"
                  type="text"
                  autoComplete="off"
                  spellCheck={false}
                  value={jsonEnricher.enrichmentName}
                  onChange={(e) => updateJsonEnricher("enrichmentName", e.currentTarget.value)}
                />
                <span className="muted" style={{ display: "block", marginTop: "0.35rem", fontSize: "0.88rem" }}>
                  Matched lookup rows are attached to the output record under this field name.
                </span>
              </label>

              <label className="form-field">
                <span className="form-label">Join key path</span>
                <input
                  className="text-input"
                  type="text"
                  autoComplete="off"
                  spellCheck={false}
                  value={jsonEnricher.joinKeyPath}
                  onChange={(e) => updateJsonEnricher("joinKeyPath", e.currentTarget.value)}
                />
                <span className="muted" style={{ display: "block", marginTop: "0.35rem", fontSize: "0.88rem" }}>
                  Field on the input record whose value is normalized and looked up in the table topic.
                </span>
              </label>

              <label className="form-field">
                <span className="form-label">
                  Join key CEL
                  <span className="muted"> (variable `key` is the extracted field)</span>
                </span>
                <textarea
                  className="text-input mono"
                  rows={4}
                  spellCheck={false}
                  value={jsonEnricher.joinKeyCel}
                  onChange={(e) => updateJsonEnricher("joinKeyCel", e.currentTarget.value)}
                />
                <span className="muted" style={{ display: "block", marginTop: "0.35rem", fontSize: "0.88rem" }}>
                  Preset normalizes multi-format account numbers (e.g. <code className="mono">1234-5678-9</code> →{" "}
                  <code className="mono">1234567809</code>); plain values pass through unchanged. The API validates the
                  expression on save.
                </span>
              </label>
            </>
          ) : null}

          {jobType === "RANDOM_SAMPLER" ? (
            <label className="form-field">
              <span className="form-label">Sample (% of messages to forward)</span>
              <input
                className="text-input"
                type="number"
                min={0}
                max={100}
                step="any"
                value={Number.isFinite(samplePercent) ? samplePercent : 0}
                onChange={(e) => {
                  const v = e.currentTarget.valueAsNumber;
                  if (Number.isFinite(v)) setSamplePercent(Math.min(100, Math.max(0, v)));
                }}
              />
              {approxOneIn != null ? (
                <span className="muted" style={{ display: "block", marginTop: "0.35rem", fontSize: "0.88rem" }}>
                  Preview JSON uses <code className="mono">rate</code> = {samplePercent / 100} (fraction). Roughly ~1 in{" "}
                  {approxOneIn} messages, on average.
                </span>
              ) : (
                <span className="muted" style={{ display: "block", marginTop: "0.35rem", fontSize: "0.88rem" }}>
                  0% forwards nothing. The API field <code className="mono">randomSamplerConfig.rate</code> must stay between 0
                  and 1 (fraction of messages), not 0–100.
                </span>
              )}
            </label>
          ) : null}
        </div>

        <div className="btn-row" style={{ marginTop: "1rem" }}>
          <button
            type="button"
            className="primary"
            onClick={onContinue}
            disabled={!canContinue}
            title={canContinue ? undefined : continueHint}
          >
            Continue to JSON editor
          </button>
        </div>
      </div>

      <div className="panel">
        <h2>Preview</h2>
        <p className="muted" style={{ marginTop: 0, fontSize: "0.9rem" }}>
          {jobType === "RANDOM_SAMPLER"
            ? "Random sampler: each record is forwarded independently with probability equal to rate (0–1). Use the percentage field above so 1% becomes rate 0.01, not 0.001."
            : isEnricher
              ? "JSON enricher: each input record's join key is normalized with the CEL expression and left-joined against the lookup topic (GlobalKTable); matches are attached under the enrichment name. The lookup topic must be on the input allowlist."
              : "First route output topic is set to the value above; other fields match the default template."}
        </p>
        <pre className="pre-block mono">{JSON.stringify(preview, null, 2)}</pre>
      </div>
    </div>
  );
}
