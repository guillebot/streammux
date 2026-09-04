import { afterEach, describe, expect, it, vi } from "vitest";
import {
  AuthRedirectError,
  apiFetch,
  buildAutheliaLoginUrl,
  isAutheliaUrl,
  looksLikeAutheliaLoginHtml,
  redirectIfUnauthenticated,
} from "./http";

function mockResponse(init: {
  status?: number;
  ok?: boolean;
  url?: string;
  type?: ResponseType;
  headers?: Record<string, string>;
  body?: string;
}): Response {
  const status = init.status ?? 200;
  const body = init.body ?? "";
  const headers = new Headers(init.headers ?? {});
  return {
    ok: init.ok ?? (status >= 200 && status < 300),
    status,
    statusText: String(status),
    url: init.url ?? "https://streammux.onelab.alticeusa.net/jobs",
    type: init.type ?? "basic",
    headers,
    clone() {
      return mockResponse(init);
    },
    async text() {
      return body;
    },
    async json() {
      return JSON.parse(body);
    },
  } as unknown as Response;
}

describe("http auth redirect helpers", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("isAutheliaUrl matches auth portal host", () => {
    expect(isAutheliaUrl("https://auth.onelab.alticeusa.net/?rd=%2F")).toBe(true);
    expect(isAutheliaUrl("https://streammux.onelab.alticeusa.net/jobs")).toBe(false);
  });

  it("looksLikeAutheliaLoginHtml requires html + marker", () => {
    expect(looksLikeAutheliaLoginHtml("<!DOCTYPE html><html>Authelia Login</html>")).toBe(true);
    expect(looksLikeAutheliaLoginHtml('{"error":"not found"}')).toBe(false);
    expect(looksLikeAutheliaLoginHtml("<html><body>random</body></html>")).toBe(false);
  });

  it("buildAutheliaLoginUrl encodes rd", () => {
    const url = buildAutheliaLoginUrl("https://streammux.onelab.alticeusa.net/#/");
    expect(url).toBe(
      "https://auth.onelab.alticeusa.net/?rd=" +
        encodeURIComponent("https://streammux.onelab.alticeusa.net/#/"),
    );
  });

  it("redirects on HTTP 401 using Location when present", async () => {
    const assign = vi.fn();
    vi.stubGlobal("location", {
      href: "https://streammux.onelab.alticeusa.net/#/",
      assign,
      origin: "https://streammux.onelab.alticeusa.net",
    });

    const res = mockResponse({
      status: 401,
      ok: false,
      headers: {
        Location: "https://auth.onelab.alticeusa.net/?rd=https%3A%2F%2Fstreammux.onelab.alticeusa.net%2Fjobs",
      },
    });

    await expect(redirectIfUnauthenticated(res)).rejects.toBeInstanceOf(AuthRedirectError);
    expect(assign).toHaveBeenCalledWith(
      "https://auth.onelab.alticeusa.net/?rd=https%3A%2F%2Fstreammux.onelab.alticeusa.net%2Fjobs",
    );
  });

  it("redirects when fetch followed redirect to Authelia HTML", async () => {
    const assign = vi.fn();
    vi.stubGlobal("location", {
      href: "https://streammux.onelab.alticeusa.net/#/",
      assign,
      origin: "https://streammux.onelab.alticeusa.net",
    });

    const res = mockResponse({
      status: 200,
      ok: true,
      url: "https://auth.onelab.alticeusa.net/?rd=https://streammux.onelab.alticeusa.net/jobs",
      headers: { "content-type": "text/html; charset=utf-8" },
      body: "<!DOCTYPE html><html><title>Authelia</title></html>",
    });

    await expect(redirectIfUnauthenticated(res)).rejects.toBeInstanceOf(AuthRedirectError);
    expect(assign).toHaveBeenCalled();
  });

  it("does not redirect on JSON API errors", async () => {
    const assign = vi.fn();
    vi.stubGlobal("location", {
      href: "https://streammux.onelab.alticeusa.net/#/",
      assign,
      origin: "https://streammux.onelab.alticeusa.net",
    });

    const res = mockResponse({
      status: 500,
      ok: false,
      headers: { "content-type": "application/json" },
      body: '{"message":"boom"}',
    });

    await expect(redirectIfUnauthenticated(res)).resolves.toBeUndefined();
    expect(assign).not.toHaveBeenCalled();
  });

  it("does not redirect on JSON 403", async () => {
    const assign = vi.fn();
    vi.stubGlobal("location", {
      href: "https://streammux.onelab.alticeusa.net/#/",
      assign,
      origin: "https://streammux.onelab.alticeusa.net",
    });

    const res = mockResponse({
      status: 403,
      ok: false,
      headers: { "content-type": "application/json" },
      body: '{"error":"forbidden"}',
    });

    await expect(redirectIfUnauthenticated(res)).resolves.toBeUndefined();
    expect(assign).not.toHaveBeenCalled();
  });

  it("apiFetch redirects before returning on 401", async () => {
    const assign = vi.fn();
    vi.stubGlobal("location", {
      href: "https://streammux.onelab.alticeusa.net/#/",
      assign,
      origin: "https://streammux.onelab.alticeusa.net",
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        mockResponse({
          status: 401,
          ok: false,
          headers: { Location: "https://auth.onelab.alticeusa.net/?rd=%2F" },
        }),
      ),
    );

    await expect(apiFetch("/jobs")).rejects.toBeInstanceOf(AuthRedirectError);
    expect(assign).toHaveBeenCalled();
  });

  it("adds X-XSRF-TOKEN on mutating requests when the cookie is set", async () => {
    document.cookie = "XSRF-TOKEN=" + encodeURIComponent("a/b+c=");
    const fetchMock = vi.fn().mockResolvedValue(mockResponse({ status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await apiFetch("/api/admin/users", { method: "POST", body: "{}" });

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    const headers = new Headers(init.headers);
    expect(headers.get("X-XSRF-TOKEN")).toBe("a/b+c=");
  });

  it("does not add X-XSRF-TOKEN on GET", async () => {
    document.cookie = "XSRF-TOKEN=should-not-leak-on-get";
    const fetchMock = vi.fn().mockResolvedValue(mockResponse({ status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await apiFetch("/api/admin/users");

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    const headers = new Headers(init.headers);
    expect(headers.get("X-XSRF-TOKEN")).toBeNull();
  });
});
