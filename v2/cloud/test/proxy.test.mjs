// Real HTTP handler and shared PostgreSQL limiter; per-client buckets behind a trusted proxy.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { randomInt, randomUUID } from "node:crypto";
import { setTimeout as sleep } from "node:timers/promises";
import { pool, sha } from "../core.mjs";
import { trustedProxies } from "../client.mjs";
import { configuration } from "../config.mjs";
const enabled =
  (process.env.DOOTAH_CLOUD_DATABASE_URL || process.env.DOOTAH_CLOUD_DATABASE_URL_FILE) && process.env.DOOTAH_TEST_TENANTS;
const { createServer } = enabled ? await import("../server.mjs") : {};
const origin = enabled ? configuration().origin : undefined;
const servers = [];
after(async () => {
  for (const s of servers) await new Promise((r) => s.close(r));
  await pool.end();
});
// Loopback is the "proxy" here; the deployment trusts only Caddy's pinned ingress address.
async function serve(trusted) {
  const server = createServer(trustedProxies(trusted));
  servers.push(server);
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  return `http://127.0.0.1:${server.address().port}`;
}
// Fresh addresses per run: limiter rows persist in the shared database.
const ipv4 = () => `198.18.${randomInt(256)}.${randomInt(1, 255)}`;
const ipv6 = () => `2001:db8:${randomInt(65536).toString(16)}:${randomInt(65536).toString(16)}::${randomInt(1, 65536).toString(16)}`;
// Limiter windows are wall-clock minutes; keep each exhaustion inside one window.
async function freshWindow() {
  const elapsed = Date.now() % 60000;
  if (elapsed > 40000) await sleep(60500 - elapsed);
}
const manifest = (base, headers) =>
  fetch(base + "/manifest", {
    headers: { "expo-app-id": randomUUID(), "eas-client-id": randomUUID(), ...headers },
  }).then((r) => r.status);
const login = (base, headers) =>
  fetch(base + "/v1/sessions", {
    method: "POST",
    headers: { Origin: origin, "Content-Type": "application/json", ...headers },
    body: JSON.stringify({ email: `${randomUUID()}@limit.test`, password: "not-a-password" }),
  }).then((r) => r.status);
async function repeat(n, send) {
  const statuses = [];
  for (let i = 0; i < n; i += 50)
    statuses.push(...(await Promise.all(Array.from({ length: Math.min(50, n - i) }, send))));
  return statuses;
}

test(
  "trusted proxy keeps anonymous delivery buckets independent per client",
  { skip: !enabled },
  async () => {
    const base = await serve("127.0.0.1");
    const a = ipv4(), b = ipv4(), c = ipv6();
    await freshWindow();
    const allowed = await repeat(600, () => manifest(base, { "X-Forwarded-For": a }));
    assert(allowed.every((s) => s === 404), "within budget");
    assert.equal(await manifest(base, { "X-Forwarded-For": a }), 429);
    assert.equal(await manifest(base, { "X-Forwarded-For": "::ffff:" + a }), 429);
    // Client-supplied hops left of the proxy-appended address cannot escape A's bucket.
    assert.equal(await manifest(base, { "X-Forwarded-For": `${b}, ${a}` }), 429);
    assert.equal(await manifest(base, { "X-Forwarded-For": b }), 404);
    assert.equal(await manifest(base, { "X-Forwarded-For": c }), 404);
    assert.equal(await manifest(base, {}), 404);
  },
);

test(
  "trusted proxy keeps login buckets independent per client",
  { skip: !enabled },
  async () => {
    const base = await serve("127.0.0.1");
    const a = ipv6(), b = ipv4();
    await freshWindow();
    const allowed = await repeat(20, () => login(base, { "X-Forwarded-For": a }));
    assert(allowed.every((s) => s === 401), "within budget");
    assert.equal(await login(base, { "X-Forwarded-For": a }), 429);
    // Same IPv6 /64, different interface identifier: same client bucket.
    assert.equal(await login(base, { "X-Forwarded-For": a.replace(/::[0-9a-f]+$/, "::abcd") }), 429);
    assert.equal(await login(base, { "X-Forwarded-For": b }), 401);
  },
);

test(
  "HTTP ignores forged forwarding headers from an untrusted direct peer",
  { skip: !enabled },
  async () => {
    const base = await serve("192.0.2.1");
    const forged = [ipv4(), ipv4(), ipv6()];
    await freshWindow();
    const window = Math.floor(Date.now() / 60000);
    for (const f of forged)
      assert.equal(
        await manifest(base, { "X-Forwarded-For": f, Forwarded: `for=${f}`, "X-Real-IP": f }),
        404,
      );
    const count = async (key) =>
      (await pool.query("SELECT count FROM rate_limits WHERE key=$1 AND window_id=$2", [sha(key), window])).rows[0]?.count;
    for (const f of [...forged, forged[2].replace(/::[0-9a-f]+$/, "::/64")])
      assert.equal(await count("delivery:" + f), undefined);
    assert((await count("delivery:127.0.0.1")) >= forged.length);
  },
);
