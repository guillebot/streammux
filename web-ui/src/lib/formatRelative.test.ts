import { describe, expect, it } from "vitest";
import { formatRelativeAgo } from "./formatRelative";

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
