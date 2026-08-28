import { describe, expect, it } from "vitest";
import {
  extractPathFromMessage,
  parsePathSteps,
  resolveJsonPathRange,
} from "./validationPathRange";

describe("extractPathFromMessage", () => {
  it("pulls the dotted+bracketed prefix from a semantic error message", () => {
    expect(
      extractPathFromMessage(
        "routeAppConfig.routes[0].filterExpression invalid: expected comparison operator",
      ),
    ).toBe("routeAppConfig.routes[0].filterExpression");
  });

  it("handles simple top-level fields", () => {
    expect(extractPathFromMessage("jobId is required")).toBe("jobId");
  });

  it("strips the JSON-pointer style '$.' prefix used by the schema layer", () => {
    expect(extractPathFromMessage("$.routeAppConfig.inputTopic: string expected")).toBe(
      "routeAppConfig.inputTopic",
    );
  });

  it("returns null when the message doesn't lead with a path token", () => {
    expect(extractPathFromMessage("deleted jobs must include a valid version")).toBe(null);
    expect(extractPathFromMessage("route outputTopic is required")).toBe(null);
    expect(extractPathFromMessage("")).toBe(null);
  });
});

describe("parsePathSteps", () => {
  it("splits dotted and bracketed segments", () => {
    expect(parsePathSteps("routeAppConfig.routes[0].filterExpression")).toEqual([
      { kind: "prop", key: "routeAppConfig" },
      { kind: "prop", key: "routes" },
      { kind: "index", index: 0 },
      { kind: "prop", key: "filterExpression" },
    ]);
  });

  it("treats non-numeric bracket contents as property keys (map entries)", () => {
    expect(parsePathSteps("alarmsToZtrConfig.mappings[myMapping]")).toEqual([
      { kind: "prop", key: "alarmsToZtrConfig" },
      { kind: "prop", key: "mappings" },
      { kind: "prop", key: "myMapping" },
    ]);
  });

  it("returns null for empty or malformed input", () => {
    expect(parsePathSteps("")).toBe(null);
    expect(parsePathSteps("routeAppConfig[0")).toBe(null);
  });
});

describe("resolveJsonPathRange", () => {
  const doc = `{
  "jobId": "job-1",
  "routeAppConfig": {
    "inputTopic": "in.topic",
    "routes": [
      { "filterExpression": "message.type == \\"A\\"", "outputTopic": "out" },
      { "filterExpression": "bad expr", "outputTopic": "out2" }
    ]
  }
}`;

  function slice(range: { from: number; to: number } | null): string | null {
    if (!range) return null;
    return doc.slice(range.from, range.to);
  }

  it("resolves a top-level property value", () => {
    const range = resolveJsonPathRange(doc, "jobId");
    expect(slice(range)).toBe('"job-1"');
  });

  it("resolves a nested property value", () => {
    const range = resolveJsonPathRange(doc, "routeAppConfig.inputTopic");
    expect(slice(range)).toBe('"in.topic"');
  });

  it("resolves an array item at a given index", () => {
    const range = resolveJsonPathRange(doc, "routeAppConfig.routes[1]");
    expect(slice(range)).toContain('"bad expr"');
  });

  it("resolves a nested property inside an array item", () => {
    const range = resolveJsonPathRange(doc, "routeAppConfig.routes[1].filterExpression");
    expect(slice(range)).toBe('"bad expr"');
  });

  it("returns null when a property is missing from the doc", () => {
    expect(resolveJsonPathRange(doc, "routeAppConfig.doesNotExist")).toBe(null);
  });

  it("returns null when an array index is out of bounds", () => {
    expect(resolveJsonPathRange(doc, "routeAppConfig.routes[9]")).toBe(null);
  });

  it("returns null when the path can't be parsed", () => {
    expect(resolveJsonPathRange(doc, "")).toBe(null);
  });
});
