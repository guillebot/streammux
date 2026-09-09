import { describe, expect, it } from "vitest";
import { formatDurationSince, formatRelativeAgo } from "./formatRelative";

const now = new Date("2026-09-08T12:00:00Z");

describe("formatRelativeAgo", () => {
  it("formats seconds", () => {
    expect(formatRelativeAgo("2026-09-08T11:59:48Z", now)).toBe("12 seconds ago");
    expect(formatRelativeAgo("2026-09-08T11:59:59Z", now)).toBe("1 second ago");
  });

  it("formats minutes", () => {
    expect(formatRelativeAgo("2026-09-08T11:55:00Z", now)).toBe("5 minutes ago");
    expect(formatRelativeAgo("2026-09-08T11:59:00Z", now)).toBe("1 minute ago");
  });

  it("formats hours with optional minutes", () => {
    expect(formatRelativeAgo("2026-09-08T09:00:00Z", now)).toBe("3 hours ago");
    expect(formatRelativeAgo("2026-09-08T09:45:00Z", now)).toBe("2h 15m ago");
  });

  it("formats days with optional hours", () => {
    expect(formatRelativeAgo("2026-09-07T06:00:00Z", now)).toBe("1d 6h ago");
    expect(formatRelativeAgo("2026-09-04T12:00:00Z", now)).toBe("4 days ago");
  });

  it("handles empty input", () => {
    expect(formatRelativeAgo(null, now)).toBe("—");
    expect(formatRelativeAgo("", now)).toBe("—");
  });
});

describe("formatDurationSince", () => {
  it("formats seconds and minutes without a suffix", () => {
    expect(formatDurationSince("2026-09-08T11:59:15Z", now)).toBe("45s");
    expect(formatDurationSince("2026-09-08T11:48:00Z", now)).toBe("12m");
  });

  it("formats hours with optional minutes", () => {
    expect(formatDurationSince("2026-09-08T06:48:00Z", now)).toBe("5h 12m");
    expect(formatDurationSince("2026-09-08T09:00:00Z", now)).toBe("3h");
  });

  it("formats days with optional hours", () => {
    expect(formatDurationSince("2026-09-05T08:00:00Z", now)).toBe("3d 4h");
    expect(formatDurationSince("2026-09-04T12:00:00Z", now)).toBe("4d");
  });

  it("handles empty and unparseable input", () => {
    expect(formatDurationSince(null, now)).toBe("—");
    expect(formatDurationSince("", now)).toBe("—");
    expect(formatDurationSince("not-a-date", now)).toBe("—");
  });
});
