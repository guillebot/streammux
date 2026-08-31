import { describe, expect, it } from "vitest";
import type { JobDefinition } from "../types";
import { applyJobTypeSwitch, defaultRouteAppConfig } from "./BasicJobForm";
import { recordToRows, rowsToRecord } from "./StringMapEditor";

function makeDef(): JobDefinition {
  return {
    jobId: "sample-job",
    jobVersion: 0,
    jobType: "ROUTE_APP",
    desiredState: "ACTIVE",
    priority: 5,
    siteAffinity: "site-a",
    leasePolicy: {
      heartbeatIntervalSeconds: 5,
      leaseDurationSeconds: 30,
      claimBackoffMillis: 1000,
      allowFailover: true,
    },
    parallelism: 1,
    routeAppConfig: {
      inputTopic: "in",
      inputFormat: "JSON",
      outputFormat: "JSON",
      protobufSchemaSubject: null,
      routes: [],
      streamProperties: {},
      serdeProperties: {},
    },
    randomSamplerConfig: null,
    labels: { env: "prod" },
    tags: ["alpha"],
    updatedAt: "2024-01-01T00:00:00Z",
    updatedBy: "tester",
  };
}

describe("StringMapEditor helpers", () => {
  it("round-trips a record through rowsToRecord ∘ recordToRows", () => {
    const record = { env: "prod", region: "us-east-1" };
    expect(rowsToRecord(recordToRows(record))).toEqual(record);
  });

  it("drops blank-key rows on serialization", () => {
    expect(
      rowsToRecord([
        { id: "a", key: "", value: "orphan" },
        { id: "b", key: "env", value: "prod" },
      ]),
    ).toEqual({ env: "prod" });
  });

  it("last-write-wins on duplicate keys", () => {
    expect(
      rowsToRecord([
        { id: "a", key: "env", value: "prod" },
        { id: "b", key: "env", value: "dev" },
      ]),
    ).toEqual({ env: "dev" });
  });

  it("trims whitespace around keys when checking for blanks", () => {
    expect(rowsToRecord([{ id: "a", key: "   ", value: "meh" }])).toEqual({});
  });
});

describe("basic form JSON round-trip", () => {
  it("preserves untouched fields when a scalar is edited", () => {
    const def = makeDef();
    const patched: JobDefinition = { ...def, jobId: "renamed" };
    const roundTripped = JSON.parse(JSON.stringify(patched)) as JobDefinition;
    expect(roundTripped.jobId).toBe("renamed");
    expect(roundTripped.jobType).toBe(def.jobType);
    expect(roundTripped.desiredState).toBe(def.desiredState);
    expect(roundTripped.labels).toEqual(def.labels);
    expect(roundTripped.tags).toEqual(def.tags);
    expect(roundTripped.routeAppConfig).toEqual(def.routeAppConfig);
    expect(roundTripped.leasePolicy).toEqual(def.leasePolicy);
  });

  it("emits new labels/tags without leaking row identity or dupes", () => {
    const def = makeDef();
    const nextLabels = rowsToRecord([
      ...recordToRows(def.labels),
      { id: "new", key: "team", value: "platform" },
    ]);
    expect(nextLabels).toEqual({ env: "prod", team: "platform" });

    const patched: JobDefinition = { ...def, labels: nextLabels, tags: [...def.tags, "beta"] };
    const roundTripped = JSON.parse(JSON.stringify(patched)) as JobDefinition;
    expect(roundTripped.labels).toEqual({ env: "prod", team: "platform" });
    expect(roundTripped.tags).toEqual(["alpha", "beta"]);
  });
});

describe("applyJobTypeSwitch", () => {
  it("clears randomSamplerConfig and populates a default routeAppConfig when switching to ROUTE_APP", () => {
    const def = makeDef();
    const asSampler: JobDefinition = {
      ...def,
      jobType: "RANDOM_SAMPLER",
      routeAppConfig: null,
      randomSamplerConfig: {
        inputTopic: "in",
        outputTopic: "out",
        rate: 0.25,
        streamProperties: {},
      },
    };
    const switched = applyJobTypeSwitch(asSampler, "ROUTE_APP");
    expect(switched.jobType).toBe("ROUTE_APP");
    expect(switched.randomSamplerConfig).toBeNull();
    expect(switched.routeAppConfig).toEqual(defaultRouteAppConfig());
  });

  it("clears routeAppConfig and populates a default randomSamplerConfig when switching to RANDOM_SAMPLER", () => {
    const def = makeDef();
    const switched = applyJobTypeSwitch(def, "RANDOM_SAMPLER");
    expect(switched.jobType).toBe("RANDOM_SAMPLER");
    expect(switched.routeAppConfig).toBeNull();
    expect(switched.randomSamplerConfig).not.toBeNull();
    expect(switched.randomSamplerConfig?.inputTopic).toBe("");
  });

  it("is non-destructive: switching to a jobType that already has config preserves it", () => {
    const def = makeDef();
    const existingRouteConfig = def.routeAppConfig;
    // Simulate: currently RANDOM_SAMPLER but the def still carries a routeAppConfig
    // (e.g. after a previous ROUTE_APP → RANDOM_SAMPLER → ROUTE_APP round-trip).
    const mixed: JobDefinition = {
      ...def,
      jobType: "RANDOM_SAMPLER",
      randomSamplerConfig: null,
    };
    const switched = applyJobTypeSwitch(mixed, "ROUTE_APP");
    expect(switched.routeAppConfig).toBe(existingRouteConfig);
  });

  it("is idempotent when the type is already the target", () => {
    const def = makeDef();
    const switched = applyJobTypeSwitch(def, "ROUTE_APP");
    expect(switched.jobType).toBe("ROUTE_APP");
    expect(switched.routeAppConfig).toBe(def.routeAppConfig);
    expect(switched.randomSamplerConfig).toBeNull();
  });
});
