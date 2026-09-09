import { describe, expect, it } from "vitest";
import {
  formatCompactCount,
  formatLagMetricsSummary,
  formatOutputCountWithPercent,
} from "./jobStatusDisplay";

describe("formatCompactCount", () => {
  it("leaves values below a thousand alone", () => {
    expect(formatCompactCount(0)).toBe("0");
    expect(formatCompactCount(842)).toBe("842");
    expect(formatCompactCount(999)).toBe("999");
  });

  it("abbreviates thousands", () => {
    expect(formatCompactCount(1234)).toBe("1.2k");
    expect(formatCompactCount(30041)).toBe("30k");
    expect(formatCompactCount(196127)).toBe("196k");
    expect(formatCompactCount(196829)).toBe("197k");
  });

  it("abbreviates millions and billions", () => {
    expect(formatCompactCount(11608739)).toBe("11.6M");
    expect(formatCompactCount(5514664)).toBe("5.5M");
    expect(formatCompactCount(2400000000)).toBe("2.4B");
  });

  it("returns em dash for missing values", () => {
    expect(formatCompactCount(null)).toBe("—");
    expect(formatCompactCount(undefined)).toBe("—");
  });
});

describe("formatOutputCountWithPercent", () => {
  it("appends integer percent when both counts are positive", () => {
    expect(formatOutputCountWithPercent(1234, 2742)).toBe("1,234(45%)");
  });

  it("omits percent when input count is zero", () => {
    expect(formatOutputCountWithPercent(1234, 0)).toBe("1,234");
  });

  it("omits percent when input count is missing", () => {
    expect(formatOutputCountWithPercent(1234, null)).toBe("1,234");
    expect(formatOutputCountWithPercent(1234, undefined)).toBe("1,234");
  });

  it("omits percent when output count is zero", () => {
    expect(formatOutputCountWithPercent(0, 1000)).toBe("0");
  });

  it("returns em dash for missing output count", () => {
    expect(formatOutputCountWithPercent(null, 1000)).toBe("—");
    expect(formatOutputCountWithPercent(undefined, 1000)).toBe("—");
  });

  it("allows percentages above 100", () => {
    expect(formatOutputCountWithPercent(50629, 1779)).toBe("50,629(2846%)");
  });
});

describe("formatLagMetricsSummary", () => {
  it("formats input and output with rate, total, and output percent", () => {
    const summary = formatLagMetricsSummary({
      inputLag: 1006,
      inputRatePerSecond: 6,
      inputCount: 1779,
      outputRatePerSecond: 44,
      outputCount: 50629,
    });

    expect(summary).toBe("in  6/s · 1,779 · lag 1,006\nout  44/s · 50,629(2846%)");
  });

  it("omits output rate when zero but still shows total with percent", () => {
    const summary = formatLagMetricsSummary({
      inputLag: 0,
      inputRatePerSecond: 33,
      inputCount: 9343,
      outputRatePerSecond: 0,
      outputCount: 3722,
    });

    expect(summary).toBe("in  33/s · 9,343\nout  3,722(40%)");
  });

  it("omits percent on output when input total is zero", () => {
    const summary = formatLagMetricsSummary({
      inputLag: 0,
      inputRatePerSecond: 10,
      inputCount: 0,
      outputRatePerSecond: 5,
      outputCount: 100,
    });

    expect(summary).toBe("in  10/s\nout  5/s · 100");
  });

  it("abbreviates every number when compact is requested", () => {
    const summary = formatLagMetricsSummary(
      {
        inputLag: 12500,
        inputRatePerSecond: 845,
        inputCount: 11608739,
        outputRatePerSecond: 62,
        outputCount: 267176,
      },
      true,
    );

    expect(summary).toBe("in  845/s · 11.6M · lag 12.5k\nout  62/s · 267k(2%)");
  });
});
