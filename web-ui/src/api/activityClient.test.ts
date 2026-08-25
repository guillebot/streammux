import { afterEach, describe, expect, it, vi } from "vitest";
import { getCurrentActor, listActivity, recordSession } from "./activityClient";

describe("activityClient", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("getCurrentActor returns actor from API", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ actor: "jsolarin" }),
      }),
    );

    await expect(getCurrentActor()).resolves.toBe("jsolarin");
    expect(fetch).toHaveBeenCalledWith("/activity/me");
  });

  it("recordSession posts to session endpoint", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        status: 202,
      }),
    );

    await recordSession();
    expect(fetch).toHaveBeenCalledWith("/activity/session", { method: "POST" });
  });

  it("listActivity builds query string", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => [],
      }),
    );

    await listActivity({ limit: 25, jobId: "job-1", eventTypes: ["PAUSED"], actor: "operator" });
    expect(fetch).toHaveBeenCalledWith("/activity?limit=25&jobId=job-1&eventType=PAUSED&actor=operator");
  });

  it("listActivity repeats eventType for each selected value", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => [],
      }),
    );

    await listActivity({ eventTypes: ["PAUSED", "STARTED"] });
    expect(fetch).toHaveBeenCalledWith("/activity?eventType=PAUSED&eventType=STARTED");
  });

  it("listActivity omits eventType when list is empty", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => [],
      }),
    );

    await listActivity({ eventTypes: [] });
    expect(fetch).toHaveBeenCalledWith("/activity");
  });
});
