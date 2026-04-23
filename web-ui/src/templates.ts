import type { JobDefinition } from "./types";

/** Placeholder bootstrap for the "New job" template. Operators should override at web build
 *  with `VITE_EXAMPLE_KAFKA_BOOTSTRAP` (see repo `.env.example`) — do NOT bake an internal
 *  hostname here, or it ships in the published image. */
export function exampleBootstrapServers(): string {
  const v = import.meta.env.VITE_EXAMPLE_KAFKA_BOOTSTRAP;
  if (typeof v === "string" && v.trim()) return v.trim();
  return "localhost:9092";
}

/** Starting point for POST /jobs; the API normalizes version and timestamps. */
export function newJobTemplate(): JobDefinition {
  const now = new Date().toISOString();
  return {
    jobId: "my-route-job",
    jobVersion: 1,
    jobType: "ROUTE_APP",
    desiredState: "ACTIVE",
    priority: 1,
    siteAffinity: "site-a",
    leasePolicy: {
      heartbeatIntervalSeconds: 10,
      leaseDurationSeconds: 30,
      claimBackoffMillis: 5000,
      allowFailover: true,
    },
    parallelism: 1,
    routeAppConfig: {
      inputTopic: "net.optimum.monitoring.netscout.fixed.voicesip.json",
      inputFormat: "JSON",
      outputFormat: "JSON",
      protobufSchemaSubject: null,
      routes: [
        {
          routeId: "route-1",
          filterExpression: 'message.type == "ALARM"',
          outputTopic: "lab.optimum.experimental.streamlens.streammux.alerts",
        },
      ],
      streamProperties: {
        "bootstrap.servers": exampleBootstrapServers(),
      },
      serdeProperties: {},
    },
    randomSamplerConfig: null,
    labels: { team: "mux" },
    tags: ["demo"],
    updatedAt: now,
    updatedBy: "web-ui",
  };
}
