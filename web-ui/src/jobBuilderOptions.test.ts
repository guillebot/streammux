import { describe, expect, it } from "vitest";
import { defaultJsonEnricherConfig } from "./basicEditor/BasicJobForm";
import {
  JOB_BUILDER_JOB_TYPES,
  buildJobDefinition,
  defaultJobBuilderJsonEnricherOptions,
} from "./jobBuilderOptions";

const BOOTSTRAP = "kafka-a:9092,kafka-b:9092";

describe("JOB_BUILDER_JOB_TYPES", () => {
  it("offers every job type the Basic editor can render", () => {
    expect(JOB_BUILDER_JOB_TYPES).toEqual(["ROUTE_APP", "RANDOM_SAMPLER", "JSON_ENRICHER"]);
  });
});

describe("buildJobDefinition — JSON_ENRICHER", () => {
  it("populates only jsonEnricherConfig, wiring topics and bootstrap from the shared fields", () => {
    const def = buildJobDefinition({
      jobId: " enrich-1 ",
      jobType: "JSON_ENRICHER",
      bootstrapServers: BOOTSTRAP,
      inputTopic: "net.optimum.in",
      outputTopic: "net.optimum.out",
      samplePercent: 25,
      jsonEnricher: {
        lookupTopic: "net.optimum.table",
        source: "csg",
        joinKeyPath: "AccountNum",
        joinKeyCel: "key",
        enrichmentName: "custdata",
      },
    });
    expect(def.jobId).toBe("enrich-1");
    expect(def.jobType).toBe("JSON_ENRICHER");
    expect(def.routeAppConfig).toBeNull();
    expect(def.randomSamplerConfig).toBeNull();
    expect(def.jsonEnricherConfig).toEqual({
      inputTopic: "net.optimum.in",
      outputTopic: "net.optimum.out",
      lookupTopic: "net.optimum.table",
      source: "csg",
      joinKeyPath: "AccountNum",
      joinKeyCel: "key",
      enrichmentName: "custdata",
      streamProperties: { "bootstrap.servers": BOOTSTRAP },
    });
  });

  it("falls back to the multi-format account-number CEL preset when no enricher options are given", () => {
    const def = buildJobDefinition({
      jobId: "enrich-2",
      jobType: "JSON_ENRICHER",
      bootstrapServers: BOOTSTRAP,
      inputTopic: "net.optimum.in",
      outputTopic: "net.optimum.out",
      samplePercent: 25,
    });
    const preset = defaultJsonEnricherConfig();
    expect(def.jsonEnricherConfig?.joinKeyCel).toBe(preset.joinKeyCel);
    expect(def.jsonEnricherConfig?.joinKeyCel).toContain('key.split("-")');
    expect(def.jsonEnricherConfig?.joinKeyPath).toBe(preset.joinKeyPath);
    expect(def.jsonEnricherConfig?.source).toBe(preset.source);
    expect(def.jsonEnricherConfig?.enrichmentName).toBe(preset.enrichmentName);
  });

  it("exposes the preset as the builder's starting enricher options", () => {
    const preset = defaultJsonEnricherConfig();
    expect(defaultJobBuilderJsonEnricherOptions()).toEqual({
      lookupTopic: preset.lookupTopic,
      source: preset.source,
      joinKeyPath: preset.joinKeyPath,
      joinKeyCel: preset.joinKeyCel,
      enrichmentName: preset.enrichmentName,
    });
  });

  it("trims free-text enricher fields", () => {
    const def = buildJobDefinition({
      jobId: "enrich-3",
      jobType: "JSON_ENRICHER",
      bootstrapServers: BOOTSTRAP,
      inputTopic: "in",
      outputTopic: "out",
      samplePercent: 0,
      jsonEnricher: {
        lookupTopic: "table",
        source: " csg ",
        joinKeyPath: " AccountNum ",
        joinKeyCel: " key \n",
        enrichmentName: " custdata ",
      },
    });
    expect(def.jsonEnricherConfig?.source).toBe("csg");
    expect(def.jsonEnricherConfig?.joinKeyPath).toBe("AccountNum");
    expect(def.jsonEnricherConfig?.joinKeyCel).toBe("key");
    expect(def.jsonEnricherConfig?.enrichmentName).toBe("custdata");
  });
});

describe("buildJobDefinition — other types clear jsonEnricherConfig", () => {
  it("ROUTE_APP leaves jsonEnricherConfig null and sets the first route output topic", () => {
    const def = buildJobDefinition({
      jobId: "route-1",
      jobType: "ROUTE_APP",
      bootstrapServers: BOOTSTRAP,
      inputTopic: "in",
      outputTopic: "out",
      samplePercent: 25,
    });
    expect(def.jobType).toBe("ROUTE_APP");
    expect(def.jsonEnricherConfig).toBeNull();
    expect(def.randomSamplerConfig).toBeNull();
    expect(def.routeAppConfig?.inputTopic).toBe("in");
    expect(def.routeAppConfig?.routes[0]?.outputTopic).toBe("out");
    expect(def.routeAppConfig?.streamProperties["bootstrap.servers"]).toBe(BOOTSTRAP);
  });

  it("RANDOM_SAMPLER leaves jsonEnricherConfig null and converts percent to rate", () => {
    const def = buildJobDefinition({
      jobId: "sample-1",
      jobType: "RANDOM_SAMPLER",
      bootstrapServers: BOOTSTRAP,
      inputTopic: "in",
      outputTopic: "out",
      samplePercent: 1,
    });
    expect(def.jobType).toBe("RANDOM_SAMPLER");
    expect(def.jsonEnricherConfig).toBeNull();
    expect(def.routeAppConfig).toBeNull();
    expect(def.randomSamplerConfig?.rate).toBe(0.01);
  });
});
