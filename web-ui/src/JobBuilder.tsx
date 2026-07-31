import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { getKafkaTopicCatalog } from "./api/client";
import {
  JOB_BUILDER_BOOTSTRAP_SERVERS,
  JOB_BUILDER_FALLBACK_INPUT_TOPICS,
  JOB_BUILDER_FALLBACK_OUTPUT_TOPICS,
  JOB_BUILDER_JOB_TYPES,
  buildJobDefinition,
} from "./jobBuilderOptions";
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
      }),
    [jobId, jobType, bootstrapServers, inputTopic, outputTopic, samplePercent],
  );

  const approxOneIn =
    jobType === "RANDOM_SAMPLER" && samplePercent > 0 ? Math.round(100 / samplePercent) : null;

  const onContinue = () => {
    stashJobDefinitionForNew(preview);
    navigate("/job/new");
  };

  const inputV = validateTopic(inputTopic, inputTopics, topicsLoading);
  const outputV = validateTopic(outputTopic, outputTopics, topicsLoading);
  const canContinue =
    !topicsLoading && !inputV.missing && !inputV.invalid && !outputV.missing && !outputV.invalid;

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
            title={canContinue ? undefined : "Pick both topics from the list to continue"}
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
            : "First route output topic is set to the value above; other fields match the default template."}
        </p>
        <pre className="pre-block mono">{JSON.stringify(preview, null, 2)}</pre>
      </div>
    </div>
  );
}
