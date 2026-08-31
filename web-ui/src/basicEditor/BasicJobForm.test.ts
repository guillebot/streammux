import { describe, expect, it } from "vitest";
import type { JobDefinition } from "../types";
import { labelsToRows, rowsToLabels } from "./BasicJobForm";

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

describe("labels editor helpers", () => {
  it("round-trips a labels record through rowsToLabels ∘ labelsToRows", () => {
    const record = { env: "prod", region: "us-east-1" };
    expect(rowsToLabels(labelsToRows(record))).toEqual(record);
  });

  it("drops blank-key rows on serialization", () => {
    expect(
      rowsToLabels([
        { id: "a", key: "", value: "orphan" },
        { id: "b", key: "env", value: "prod" },
      ]),
    ).toEqual({ env: "prod" });
  });

  it("last-write-wins on duplicate keys", () => {
    expect(
      rowsToLabels([
        { id: "a", key: "env", value: "prod" },
        { id: "b", key: "env", value: "dev" },
      ]),
    ).toEqual({ env: "dev" });
  });

  it("trims whitespace around keys when checking for blanks", () => {
    expect(rowsToLabels([{ id: "a", key: "   ", value: "meh" }])).toEqual({});
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
    const nextLabels = rowsToLabels([
      ...labelsToRows(def.labels),
      { id: "new", key: "team", value: "platform" },
    ]);
    expect(nextLabels).toEqual({ env: "prod", team: "platform" });

    const patched: JobDefinition = { ...def, labels: nextLabels, tags: [...def.tags, "beta"] };
    const roundTripped = JSON.parse(JSON.stringify(patched)) as JobDefinition;
    expect(roundTripped.labels).toEqual({ env: "prod", team: "platform" });
    expect(roundTripped.tags).toEqual(["alpha", "beta"]);
  });
});
