import { describe, expect, it } from "vitest";
import { sessionHasAdmin, type SessionInfo } from "./sessionClient";

describe("sessionHasAdmin", () => {
  it("is false for proxy/legacy sessions even if role says admin", () => {
    const session: SessionInfo = { username: "operator", role: "admin", authType: "PROXY" };
    expect(sessionHasAdmin(session)).toBe(false);
  });

  it("is true when role or roles includes admin", () => {
    expect(
      sessionHasAdmin({ username: "a", role: "admin", authType: "LOCAL" }),
    ).toBe(true);
    expect(
      sessionHasAdmin({ username: "a", role: "viewer", roles: ["viewer", "admin"], authType: "OIDC" }),
    ).toBe(true);
    expect(
      sessionHasAdmin({ username: "a", role: "viewer", roles: ["viewer"], authType: "LOCAL" }),
    ).toBe(false);
  });
});
