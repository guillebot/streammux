import type { JobDefinition, JobType } from "./types";
import { exampleBootstrapServers, newJobTemplate } from "./templates";

export const JOB_BUILDER_BOOTSTRAP_SERVERS: string[] = [
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
  "lab.optimum.experimental.streamlens.streammux.alerts",
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
