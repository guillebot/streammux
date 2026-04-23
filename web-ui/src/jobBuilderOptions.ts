import type { JobDefinition, JobType } from "./types";
import { exampleBootstrapServers, newJobTemplate } from "./templates";

/**
 * Static lists for the Job Builder UI. Replace input/output topic sources with a broker API when available.
 */
export const JOB_BUILDER_BOOTSTRAP_SERVERS: string[] = [
  exampleBootstrapServers(),
  "localhost:9092",
  "kafka:9092",
];

/** Defaults must satisfy typical `STREAMMUX_ALLOWED_*` rules (see repo `.env.example`). */
const EXAMPLE_ALLOWED_INPUT = "net.optimum.monitoring.netscout.fixed.voicesip.json";
const EXAMPLE_OUTPUT_PREFIX = "lab.optimum.experimental.streamlens.streammux.";

export const JOB_BUILDER_INPUT_TOPICS: string[] = [
  EXAMPLE_ALLOWED_INPUT,
  "net.optimum.monitoring.example-in",
  "events-raw",
  "telemetry-in",
];

export const JOB_BUILDER_OUTPUT_TOPICS: string[] = [
  `${EXAMPLE_OUTPUT_PREFIX}alerts`,
  `${EXAMPLE_OUTPUT_PREFIX}events-processed`,
  `${EXAMPLE_OUTPUT_PREFIX}telemetry-out`,
];

export const JOB_BUILDER_JOB_TYPES: JobType[] = ["ROUTE_APP", "RANDOM_SAMPLER"];

export function buildJobDefinition(options: {
  jobId: string;
  jobType: JobType;
  bootstrapServers: string;
  inputTopic: string;
  outputTopic: string;
  /** Percent of messages to forward (0–100); stored in the API as `rate = samplePercent / 100`. */
  samplePercent: number;
}): JobDefinition {
  const base = newJobTemplate();
  if (options.jobType === "RANDOM_SAMPLER") {
    const p = Math.min(100, Math.max(0, options.samplePercent));
    return {
      ...base,
      jobId: options.jobId.trim() || base.jobId,
      jobType: "RANDOM_SAMPLER",
      routeAppConfig: null,
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
  };
}
