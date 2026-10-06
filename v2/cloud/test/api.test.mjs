// Run against an isolated migrated test database and operator-provisioned tenants.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
const config = process.env.DOOTAH_TEST_TENANTS;
const tenants = config ? JSON.parse(await readFile(config)) : null;
const base = process.env.DOOTAH_TEST_ORIGIN ?? "http://127.0.0.1:3100";
async function call(a, path, method = "GET", body, extra = {}) {
  const r = await fetch(base + "/v1/" + path, {
    method,
    headers: {
      Authorization: `Bearer ${a.token}`,
      "Dootah-Organization": a.org,
      "Content-Type": "application/json",
      "Idempotency-Key": randomUUID(),
      ...extra,
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  return { status: r.status, body: await r.json() };
}
async function session(a, path, method, body) {
  const r = await fetch(base + "/v1/" + path, {
    method,
    headers: a.headers,
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  return { status: r.status, body: await r.json() };
}
test(
  "tenant isolation and scoped tokens across all control/read paths",
  { skip: !tenants },
  async () => {
    const { a, b } = tenants;
    for (const [table, id] of [
      ["apps", b.app],
      ["environments", b.environment],
    ])
      assert.equal((await call(a, `${table}/${id}`)).status, 404);
    assert.equal((await call(a, `apps/${a.app}`)).status, 200);
    assert.equal(
      (await call(a, "apps", "GET", null, { "Dootah-Organization": b.org }))
        .status,
      404,
    );
    const list = await call(a, "apps");
    assert(
      list.body.items.every(
        (x) => x.organization_id === a.org && x.id === a.app,
      ),
    );
    assert.equal(
      (
        await call(a, "releases", "POST", {
          environmentId: b.environment,
          revision: "cross",
          artifact: {},
        })
      ).status,
      404,
    );
    for (const action of ["rollout", "pause", "resume", "rollback", "kill"])
      assert.equal(
        (
          await call(a, `releases/${randomUUID()}/${action}`, "POST", {
            version: 1,
            percentage: 100,
          })
        ).status,
        404,
      );
    assert.equal(
      (await call(a, "apps/34e81d97-661f-4f3b-ad2c-8d4e7a6546a6")).status,
      404,
    );
    const token = await session(a, "tokens", "POST", {
      scopes: ["telemetry:read"],
      expiresDays: 1,
      appId: a.app,
      environmentId: a.environment,
    });
    assert.equal(token.status, 200);
    const viewer = { ...a, token: token.body.secret };
    assert.equal((await call(viewer, "installations")).status, 200);
    assert.equal((await call(viewer, "releases", "POST", {})).status, 403);
    assert.equal(
      (
        await call(viewer, `releases/${randomUUID()}/rollout`, "POST", {
          version: 1,
          percentage: 100,
        })
      ).status,
      403,
    );
    assert.equal(
      (await session(a, `tokens/${token.body.id}`, "DELETE")).status,
      200,
    );
    assert.equal((await call(viewer, "installations")).status, 401);
    assert.equal((await call(a, "tokens", "POST", {})).status, 403);
  },
);
test(
  "sessions require CSRF and exact origin; untrusted ids and oversized bodies fail",
  { skip: !tenants },
  async () => {
    const a = tenants.a;
    const r = await fetch(base + "/v1/apps", {
      method: "POST",
      headers: {
        Cookie: a.headers.Cookie,
        "Dootah-Organization": a.org,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ name: "csrf" }),
    });
    assert.equal(r.status, 403);
    assert.equal((await call(a, "apps/not-a-uuid")).status, 404);
    assert.equal((await call(a, "apps?limit=100000")).status, 422);
    assert.equal((await call(a, "events", "POST", { events: [] })).status, 403);
    const huge = await fetch(base + "/v1/releases", {
      method: "POST",
      headers: { Authorization: `Bearer ${a.token}` },
      body: " ".repeat(131073),
    });
    assert.equal(huge.status, 413);
  },
);
test(
  "installation authorization, tenant boundaries, deduplication and bounded telemetry",
  { skip: !tenants },
  async () => {
    const { a, b } = tenants;
    const ticket = await call(a, "enrollment-tickets", "POST", {
      environmentId: a.environment,
    });
    assert.equal(ticket.status, 200);
    const id = randomUUID(),
      embedded = randomUUID(),
      auth = { ...a, token: ticket.body.secret };
    const enrollment = {
      installationId: id,
      runtime: "dootah-v2-logic-2",
      nativeVersion: "test",
      sdkVersion: "test",
      embeddedId: embedded,
    };
    assert.equal(
      (await call({ ...auth, org: b.org }, "enroll", "POST", enrollment))
        .status,
      404,
    );
    const enrolled = await call(auth, "enroll", "POST", enrollment);
    assert.equal(enrolled.status, 200);
    assert.equal((await call(auth, "enroll", "POST", enrollment)).status, 401);
    const device = { ...a, token: enrolled.body.credential };
    const e = {
      id: randomUUID(),
      installationId: id,
      updateId: embedded,
      type: "update_activated",
      activationId: randomUUID(),
      timestamp: new Date().toISOString(),
      runtimeAbi: 2,
      logicAbi: 1,
      metadata: {},
    };
    assert.equal(
      (await call(device, "events", "POST", { events: [e] })).status,
      200,
    );
    assert.equal(
      (await call(device, "events", "POST", { events: [e] })).status,
      200,
    );
    assert.equal(
      (
        await call(device, "events", "POST", {
          events: [{ ...e, type: "update_healthy" }],
        })
      ).status,
      409,
    );
    assert.equal(
      (await call({ ...device, org: b.org }, "events", "POST", { events: [e] }))
        .status,
      404,
    );
    assert.equal(
      (
        await call(device, "events", "POST", {
          events: [{ ...e, id: randomUUID(), installationId: randomUUID() }],
        })
      ).status,
      422,
    );
    assert.equal(
      (
        await call(device, "events", "POST", {
          events: [{ ...e, id: randomUUID(), updateId: randomUUID() }],
        })
      ).status,
      422,
    );
    assert.equal(
      (
        await call(device, "events", "POST", {
          events: [{ ...e, id: randomUUID(), metadata: { log: "arbitrary" } }],
        })
      ).status,
      422,
    );
    assert.equal(
      (await call(device, "events", "POST", { events: Array(33).fill(e) }))
        .status,
      422,
    );
    assert.equal((await call(device, "apps")).status, 403);
  },
);
test(
  "same-organization app scopes deny sibling apps and invalid list filters fail validation",
  { skip: !tenants },
  async () => {
    const a = tenants.a;
    const sibling = await session(a, "apps", "POST", {
      name: "Scoped sibling",
    });
    assert.equal(sibling.status, 200);
    assert.equal((await call(a, "apps/" + sibling.body.id)).status, 404);
    assert.equal((await call(a, "apps?releaseId=" + randomUUID())).status, 422);
    assert.equal(
      (await call(a, "environments?environmentId=" + randomUUID())).status,
      422,
    );
  },
);
test(
  "membership changes restrict existing tokens; last owner cannot be removed",
  { skip: !tenants || !process.env.DOOTAH_TEST_PASSWORD_FILE },
  async () => {
    const a = tenants.a,
      baseHeaders = { ...a.headers };
    const team = await session(a, "members", "GET");
    assert.equal(team.status, 200);
    const own = team.body.items.find((u) => u.email === "owner-a@local.test");
    assert.equal(
      (await session(a, `members/${own.user_id}`, "DELETE")).status,
      409,
    );
    const email = "viewer@local.test";
    let user = team.body.items.find((u) => u.email === email);
    if (user)
      assert.equal(
        (
          await session(a, `members/${user.user_id}`, "PATCH", {
            role: "Owner",
          })
        ).status,
        200,
      );
    else {
      const add = await session(a, "members", "POST", { email, role: "Owner" });
      assert.equal(add.status, 200);
      user = { user_id: add.body.id };
    }
    const login = await fetch(base + "/v1/sessions", {
      method: "POST",
      headers: { Origin: base, "Content-Type": "application/json" },
      body: JSON.stringify({
        email,
        password: await readFile(process.env.DOOTAH_TEST_PASSWORD_FILE, "utf8"),
      }),
    });
    assert.equal(login.status, 200);
    const logged = await login.json();
    const member = {
      ...a,
      headers: {
        ...a.headers,
        Cookie: login.headers.get("set-cookie").split(";")[0],
        "X-CSRF-Token": logged.csrf,
      },
    };
    const token = await session(member, "tokens", "POST", {
      scopes: ["release:publish", "telemetry:read"],
      expiresDays: 1,
      appId: a.app,
      environmentId: a.environment,
    });
    assert.equal(token.status, 200);
    assert.equal(
      (await session(a, `members/${user.user_id}`, "PATCH", { role: "Viewer" }))
        .status,
      200,
    );
    const scoped = { ...a, token: token.body.secret };
    assert.equal((await call(scoped, "releases", "POST", {})).status, 403);
    assert.equal((await call(scoped, "installations")).status, 200);
    assert.equal(
      (await session(a, `members/${user.user_id}`, "DELETE")).status,
      200,
    );
    assert.equal((await call(scoped, "installations")).status, 404);
  },
);
