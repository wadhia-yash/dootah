import { describe, it, expect, vi } from "vitest";
import {
  ApiError,
  Cloud,
  canControl,
  createIntent,
  percent,
  type Release,
} from "./cloud";
import { applyDemo, demoReleases } from "./demo";
const release: Release = { ...demoReleases[0] };
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json" },
  });
describe("Cloud release controls", () => {
  it("calls the global fetch unbound by default, as browsers require", async () => {
    const cloud = new Cloud();
    const strictFetch = vi.fn(function (this: unknown) {
      if (this !== undefined && this !== globalThis)
        throw new TypeError("Illegal invocation");
      return Promise.resolve(json({ items: [] }));
    });
    vi.stubGlobal("fetch", strictFetch);
    try {
      await expect(cloud.request("organizations")).resolves.toEqual({
        items: [],
      });
      expect(strictFetch).toHaveBeenCalledOnce();
    } finally {
      vi.unstubAllGlobals();
    }
  });
  it("reuses the exact rollback identity after an ambiguous network failure", async () => {
    const transport = vi
      .fn()
      .mockRejectedValueOnce(new TypeError("network"))
      .mockResolvedValueOnce(json({ id: "operation", status: "pending" }));
    const cloud = new Cloud(transport);
    cloud.csrf = "csrf";
    cloud.org = "org";
    const intent = createIntent(
      release,
      "rollback",
      undefined,
      "Rendering regression",
    );
    await expect(cloud.control(intent)).rejects.toMatchObject({
      code: "network_error",
    });
    await cloud.control(intent);
    expect(transport.mock.calls[0][0]).toEqual(transport.mock.calls[1][0]);
    expect(transport.mock.calls[0][1].headers).toEqual(
      transport.mock.calls[1][1].headers,
    );
    expect(transport.mock.calls[0][1].body).toEqual(
      transport.mock.calls[1][1].body,
    );
    const request = transport.mock.calls[1][1];
    expect(request.credentials).toBe("same-origin");
    expect(request.headers).toMatchObject({
      "X-CSRF-Token": "csrf",
      "Dootah-Organization": "org",
      "Idempotency-Key": intent.key,
    });
    expect(JSON.parse(request.body)).toEqual({
      version: 3,
      reason: "Rendering regression",
    });
  });
  it("does not silently retry a stale write with a newer version", async () => {
    const transport = vi
      .fn()
      .mockResolvedValue(json({ error: { code: "stale_version" } }, 409));
    const cloud = new Cloud(transport);
    await expect(
      cloud.control(createIntent(release, "rollout", 50)),
    ).rejects.toMatchObject({ code: "stale_version", status: 409 });
    expect(transport).toHaveBeenCalledTimes(1);
  });
  it("propagates authorization and csrf failure instead of treating it as successful control", async () => {
    for (const code of ["forbidden", "csrf"]) {
      const cloud = new Cloud(
        vi.fn().mockResolvedValue(json({ error: { code } }, 403)),
      );
      await expect(
        cloud.control(createIntent(release, "pause")),
      ).rejects.toMatchObject({ code, status: 403 });
    }
  });
  it("captures login csrf in memory and clears old tenant context", async () => {
    const transport = vi
      .fn()
      .mockResolvedValueOnce(json({ csrf: "new-csrf" }))
      .mockResolvedValueOnce(json({ items: [] }));
    const cloud = new Cloud(transport);
    cloud.csrf = "old";
    cloud.org = "old-org";
    await cloud.login("test@example.com", "not-a-real-password");
    expect(cloud.csrf).toBe("new-csrf");
    expect(cloud.org).toBe("");
    expect(transport.mock.calls[0][1].headers["Dootah-Organization"]).toBe("");
  });
  it("loads every page and encodes the cursor", async () => {
    const transport = vi
      .fn()
      .mockResolvedValueOnce(
        json({ items: [{ id: "one" }], nextCursor: "a:b" }),
      )
      .mockResolvedValueOnce(
        json({ items: [{ id: "two" }], nextCursor: null }),
      );
    const cloud = new Cloud(transport);
    expect(await cloud.all("releases?appId=app")).toEqual([
      { id: "one" },
      { id: "two" },
    ]);
    expect(transport.mock.calls[1][0]).toBe(
      "/v1/releases?appId=app&after=a%3Ab",
    );
  });
  it("fails instead of looping on a repeated pagination cursor", async () => {
    const transport = vi
      .fn()
      .mockResolvedValue(json({ items: [], nextCursor: "same" }));
    await expect(new Cloud(transport).all("releases")).rejects.toBeInstanceOf(
      ApiError,
    );
    expect(transport).toHaveBeenCalledTimes(2);
  });
  it("rejects malformed service responses", async () => {
    const transport = vi
      .fn()
      .mockResolvedValue(new Response("<h1>Unavailable</h1>", { status: 502 }));
    await expect(
      new Cloud(transport).request("releases"),
    ).rejects.toMatchObject({ code: "service_unavailable", status: 502 });
  });
});
describe("release state and metrics", () => {
  it("only permits resume/rollback/kill on the environment head", () => {
    expect(canControl(release, "rollback", release.id)).toBe(true);
    expect(canControl(release, "rollback", "other")).toBe(false);
    expect(canControl(release, "kill", null)).toBe(false);
    expect(
      canControl({ ...release, status: "paused" }, "resume", "other"),
    ).toBe(false);
    expect(
      canControl({ ...release, status: "paused" }, "resume", release.id),
    ).toBe(true);
  });
  it("blocks controls for pending operations and terminal or unpublished releases", () => {
    for (const action of [
      "pause",
      "resume",
      "rollout",
      "rollback",
      "kill",
    ] as const) {
      expect(
        canControl(
          {
            ...release,
            operation: { id: "op", action: "rollback", status: "pending" },
          },
          action,
          release.id,
        ),
      ).toBe(false);
      for (const status of [
        "draft",
        "failed",
        "rolled_back",
        "killed",
        "unknown",
      ])
        expect(canControl({ ...release, status }, action, release.id)).toBe(
          false,
        );
    }
    expect(
      canControl({ ...release, status: "paused" }, "rollout", release.id),
    ).toBe(false);
  });
  it("preserves unknown metric denominators rather than claiming zero", () => {
    expect(percent(null)).toBe("—");
    expect(percent(undefined)).toBe("—");
    expect(percent(0)).toBe("0.0%");
  });
  it("changes demo data without mutating fixtures or inventing fresh telemetry", () => {
    const updated = applyDemo(release, createIntent(release, "rollout", 50));
    expect(updated.percentage).toBe(50);
    expect(updated.version).toBe(4);
    expect(updated.metrics).toBeUndefined();
    expect(release.percentage).toBe(25);
    expect(
      applyDemo(release, createIntent(release, "rollback", undefined, "Demo"))
        .status,
    ).toBe("rolled_back");
  });
});

describe("account sessions", () => {
  it("registers before login without persisting credentials in browser storage", async () => {
    const transport = vi.fn()
      .mockResolvedValueOnce(json({ id: 'user' }, 201))
      .mockResolvedValueOnce(json({ csrf: 'csrf' }))
      .mockResolvedValueOnce(json({ items: [] }));
    const c = new Cloud(transport);
    await c.register('test@example.invalid', 'test-password-only');
    expect(transport.mock.calls.map(c => c[0])).toEqual(['/v1/users', '/v1/sessions', '/v1/organizations']);
    expect(c.csrf).toBe('csrf');
  });
  it("restores CSRF from the HttpOnly session cookie and rejects expired sessions", async () => {
    const transport = vi.fn()
      .mockResolvedValueOnce(json({ csrf: 'restored' }))
      .mockResolvedValueOnce(json({ items: [{ id: 'org' }] }));
    const c = new Cloud(transport);
    c.org = 'stale';
    expect(await c.restore()).toEqual({ items: [{ id: 'org' }] });
    expect(c.csrf).toBe('restored');
    expect(c.org).toBe('');
    expect(transport.mock.calls[0][1].credentials).toBe('same-origin');
    const expired = new Cloud(vi.fn().mockResolvedValue(json({ error: { code: 'unauthenticated' } }, 401)));
    await expect(expired.restore()).rejects.toMatchObject({ status: 401 });
  });
});
