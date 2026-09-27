import type { JobDefinition, JobType, JsonEnricherConfig } from "./types";
import { defaultJsonEnricherConfig } from "./basicEditor/BasicJobForm";
import { exampleBootstrapServers, newJobTemplate } from "./templates";

/** Rednet PNR Kafka (kb101–kb105 :9095) — primary data plane for stream jobs. */
export const REDNET_PNR_KAFKA_BOOTSTRAP =
  "kb101.srv.hcvlny.alticeusa.net:9095,kb102.srv.hcvlny.alticeusa.net:9095,kb103.srv.hcvlny.alticeusa.net:9095,kb104.srv.hcvlny.alticeusa.net:9095,kb105.srv.hcvlny.alticeusa.net:9095";

export const JOB_BUILDER_BOOTSTRAP_SERVERS: string[] = [
  REDNET_PNR_KAFKA_BOOTSTRAP,
  exampleBootstrapServers(),
  "localhost:9092",
  "kafka:9092",
];

/** Used only when the broker topic catalog API is unavailable. */
export const JOB_BUILDER_FALLBACK_INPUT_TOPICS: string[] = [
  "net.optimum.monitoring.netscout.fixed.voicesip.json",
];

/** Used only when the broker topic catalog API is unavailable. */
export const JOB_BUILDER_FALLBACK_OUTPUT_TOPICS: string[] = [
  "net.optimum.experimental.streamlens.streammux.alerts",
];

export const JOB_BUILDER_JOB_TYPES: JobType[] = ["ROUTE_APP", "RANDOM_SAMPLER", "JSON_ENRICHER"];

/** JSON_ENRICHER-only builder inputs; topics and bootstrap come from the shared fields. */
export type JobBuilderJsonEnricherOptions = Pick<
  JsonEnricherConfig,
  "lookupTopic" | "source" | "joinKeyPath" | "joinKeyCel" | "enrichmentName"
>;

/** Starting values for the JSON_ENRICHER fields (multi-format account-number CEL preset). */
export function defaultJobBuilderJsonEnricherOptions(): JobBuilderJsonEnricherOptions {
  const d = defaultJsonEnricherConfig();
  return {
    lookupTopic: d.lookupTopic,
    source: d.source,
    joinKeyPath: d.joinKeyPath,
    joinKeyCel: d.joinKeyCel,
    enrichmentName: d.enrichmentName,
  };
}

export function buildJobDefinition(options: {
  jobId: string;
  jobType: JobType;
  bootstrapServers: string;
  inputTopic: string;
  outputTopic: string;
  /** Percent of messages to forward (0–100); stored in the API as `rate = samplePercent / 100`. */
  samplePercent: number;
  /** Only read when `jobType` is `JSON_ENRICHER`; falls back to the preset when omitted. */
  jsonEnricher?: JobBuilderJsonEnricherOptions;
}): JobDefinition {
  const base = newJobTemplate();
  if (options.jobType === "JSON_ENRICHER") {
    const enricher = options.jsonEnricher ?? defaultJobBuilderJsonEnricherOptions();
    return {
      ...base,
      jobId: options.jobId.trim() || base.jobId,
      jobType: "JSON_ENRICHER",
      routeAppConfig: null,
      randomSamplerConfig: null,
      jsonEnricherConfig: {
        ...defaultJsonEnricherConfig(),
        inputTopic: options.inputTopic,
        outputTopic: options.outputTopic,
        lookupTopic: enricher.lookupTopic,
        source: enricher.source.trim(),
        joinKeyPath: enricher.joinKeyPath.trim(),
        joinKeyCel: enricher.joinKeyCel.trim(),
        enrichmentName: enricher.enrichmentName.trim(),
        streamProperties: {
          "bootstrap.servers": options.bootstrapServers,
        },
      },
    };
  }
  if (options.jobType === "RANDOM_SAMPLER") {
    const p = Math.min(100, Math.max(0, options.samplePercent));
    return {
      ...base,
      jobId: options.jobId.trim() || base.jobId,
      jobType: "RANDOM_SAMPLER",
      routeAppConfig: null,
      jsonEnricherConfig: null,
      randomSamplerConfig: {
        inputTopic: options.inputTopic,
        outputTopic: options.outputTopic,
        rate: p / 100,
        streamProperties: {
          "bootstrap.servers": options.bootstrapServers,
        },
      },
    };
  }
  const routeCfg = base.routeAppConfig!;
  const routes = routeCfg.routes.length > 0 ? [...routeCfg.routes] : [];
  if (routes.length === 0) {
    routes.push({
      routeId: "route-1",
      filterExpression: 'message.type == "ALARM"',
      outputTopic: options.outputTopic,
    });
  } else {
    routes[0] = { ...routes[0], outputTopic: options.outputTopic };
  }
  return {
    ...base,
    jobId: options.jobId.trim() || base.jobId,
    jobType: options.jobType,
    routeAppConfig: {
      ...routeCfg,
      inputTopic: options.inputTopic,
      routes,
      streamProperties: {
        ...routeCfg.streamProperties,
        "bootstrap.servers": options.bootstrapServers,
      },
    },
    randomSamplerConfig: null,
    jsonEnricherConfig: null,
  };
}
