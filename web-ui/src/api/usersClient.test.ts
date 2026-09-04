import { afterEach, describe, expect, it, vi } from "vitest";
import { createLocalUser, listAdminUsers, updateUserRoles } from "./usersClient";

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    async json() {
      return body;
    },
    async text() {
      return JSON.stringify(body);
    },
  } as Response;
}

describe("usersClient", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("lists users from /api/admin/users", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([{ username: "alice", roles: ["admin"] }]));
    vi.stubGlobal("fetch", fetchMock);
    const users = await listAdminUsers();
    expect(users[0].username).toBe("alice");
    expect(String(fetchMock.mock.calls[0][0])).toBe("/api/admin/users");
  });

  it("encodes username in role updates", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ username: "a@x", roles: ["viewer"] }));
    vi.stubGlobal("fetch", fetchMock);
    await updateUserRoles("a@x", ["viewer"]);
    expect(String(fetchMock.mock.calls[0][0])).toBe("/api/admin/users/a%40x/roles");
  });

  it("surfaces API error message", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse({ error: "last_admin", message: "Cannot remove the last enabled admin." }, 409),
      ),
    );
    await expect(createLocalUser({ username: "x", password: "abcdefgh", roles: ["viewer"] })).rejects.toThrow(
      "Cannot remove the last enabled admin.",
    );
  });
});
