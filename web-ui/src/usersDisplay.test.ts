import { describe, expect, it } from "vitest";
import { formatLastLogin, userStatus } from "./usersDisplay";

describe("formatLastLogin", () => {
  const now = Date.parse("2026-09-04T15:00:00Z");

  it("returns em dash when missing", () => {
    expect(formatLastLogin(null, now)).toBe("—");
    expect(formatLastLogin(undefined, now)).toBe("—");
  });

  it("formats relative past and future", () => {
    expect(formatLastLogin("2026-09-04T14:59:50Z", now)).toBe("10s ago");
    expect(formatLastLogin("2026-09-04T14:45:00Z", now)).toBe("15m ago");
    expect(formatLastLogin("2026-09-04T13:00:00Z", now)).toBe("2h ago");
    expect(formatLastLogin("2026-08-31T15:00:00Z", now)).toBe("4d ago");
    expect(formatLastLogin("2026-09-04T15:00:10Z", now)).toBe("in 10s");
  });
});

describe("userStatus", () => {
  const now = Date.parse("2026-09-04T15:00:00Z");

  it("disabled beats lockout", () => {
    expect(userStatus(false, "2026-09-04T16:00:00Z", now)).toBe("disabled");
  });

  it("locked when lockout is in the future", () => {
    expect(userStatus(true, "2026-09-04T16:00:00Z", now)).toBe("locked");
    expect(userStatus(true, "2026-09-04T14:00:00Z", now)).toBe("active");
    expect(userStatus(true, null, now)).toBe("active");
  });
});
