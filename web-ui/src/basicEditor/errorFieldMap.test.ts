import { describe, expect, it } from "vitest";
import {
  isErrorOnField,
  isErrorUnderField,
  normalizeErrorPath,
} from "./errorFieldMap";

describe("normalizeErrorPath", () => {
  it("returns null for missing / empty input", () => {
    expect(normalizeErrorPath(null)).toBeNull();
    expect(normalizeErrorPath(undefined)).toBeNull();
    expect(normalizeErrorPath("")).toBeNull();
  });

  it("strips a leading $. schema-path prefix", () => {
    expect(normalizeErrorPath("$.jobId")).toBe("jobId");
    expect(normalizeErrorPath("$.routeAppConfig.inputTopic")).toBe(
      "routeAppConfig.inputTopic",
    );
  });

  it("keeps numeric array indices as [N]", () => {
    expect(
      normalizeErrorPath("routeAppConfig.routes[0].filterExpression"),
    ).toBe("routeAppConfig.routes[0].filterExpression");
  });

  it("collapses non-numeric bracket keys to dotted property access", () => {
    // parsePathSteps treats `[env]` as a property step, so bracket-quoted keys
    // normalize to the equivalent dotted form; this keeps callers' match logic
    // simple (they only ever compare against dotted paths + numeric indices).
    expect(normalizeErrorPath("labels[env]")).toBe("labels.env");
  });
});

describe("isErrorOnField", () => {
  it("matches only when the paths are identical", () => {
    expect(isErrorOnField("jobId", "jobId")).toBe(true);
    expect(isErrorOnField("jobId", "priority")).toBe(false);
    expect(isErrorOnField(null, "jobId")).toBe(false);
  });
});

describe("isErrorUnderField", () => {
  it("matches on exact hit", () => {
    expect(isErrorUnderField("routeAppConfig", "routeAppConfig")).toBe(true);
  });

  it("matches on dotted descendants", () => {
    expect(
      isErrorUnderField(
        "routeAppConfig.routes[0].filterExpression",
        "routeAppConfig",
      ),
    ).toBe(true);
    expect(
      isErrorUnderField(
        "routeAppConfig.routes[0].filterExpression",
        "routeAppConfig.routes[0]",
      ),
    ).toBe(true);
  });

  it("does not match sibling paths that share a prefix substring", () => {
    // routeAppConfig.routesX should not be highlighted when the error targets
    // routeAppConfig.routes[0]. The trailing `.` or `[` guard prevents this.
    expect(
      isErrorUnderField("routeAppConfigExtras.routes[0]", "routeAppConfig"),
    ).toBe(false);
    expect(
      isErrorUnderField(
        "routeAppConfig.routes[10].filterExpression",
        "routeAppConfig.routes[1]",
      ),
    ).toBe(false);
  });

  it("returns false when errorPath is missing", () => {
    expect(isErrorUnderField(null, "jobId")).toBe(false);
    expect(isErrorUnderField(undefined, "jobId")).toBe(false);
  });
});
