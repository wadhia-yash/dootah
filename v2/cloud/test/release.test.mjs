import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { randomUUID, createHash, verify, X509Certificate } from "node:crypto";
const enabled =
  process.env.DOOTAH_TEST_TENANTS && process.env.DOOTAH_TEST_ARTIFACT;
const tenants = enabled
  ? JSON.parse(await readFile(process.env.DOOTAH_TEST_TENANTS))
  : null;
const artifact = enabled
  ? JSON.parse(await readFile(process.env.DOOTAH_TEST_ARTIFACT))
  : null;
const base = process.env.DOOTAH_TEST_ORIGIN ?? "http://127.0.0.1:3100";
async function request(a, path, method = "GET", body, key = randomUUID()) {
  const r = await fetch(base + "/v1/" + path, {
    method,
    headers: {
      Authorization: `Bearer ${a.token}`,
      "Content-Type": "application/json",
      "Idempotency-Key": key,
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  return { status: r.status, body: await r.json() };
}
async function wait(a, op) {
  for (let i = 0; i < 30; i++) {
    const r = await request(a, "operations/" + op.id);
    assert.equal(r.status, 200);
    if (r.body.status === "succeeded") return r.body;
    assert.notEqual(r.body.status, "failed");
    await new Promise((r) => setTimeout(r, 500));
  }
  assert.fail("operation did not complete");
}
test(
  "real signed backend: tenant release denial, idempotency, controls, rollback and terminal kill",
  { skip: !enabled, timeout: 60000 },
  async () => {
    const a = tenants.b,
      foreign = tenants.a;
    const key = randomUUID(),
      body = {
        environmentId: a.environment,
        revision: "integration tests",
        artifact,
      };
    for (const incompatible of [
      { ...artifact, runtimeAbi: 999 },
      { ...artifact, logicAbi: 999 },
      { ...artifact, runtimeVersion: "unregistered-runtime" },
    ]) {
      assert.equal(
        (await request(a, "releases", "POST", { ...body, artifact: incompatible }))
          .status,
        422,
      );
    }
    const publish = await request(a, "releases", "POST", body, key);
    assert.equal(publish.status, 200);
    const op = publish.body;
    assert.equal(
      (await request(a, "releases", "POST", body, key)).body.id,
      op.id,
    );
    assert.equal(
      (
        await request(
          a,
          "releases",
          "POST",
          { ...body, revision: "changed" },
          key,
        )
      ).status,
      409,
    );
    const id = op.resource_id;
    assert.equal((await request(foreign, "releases/" + id)).status, 404);
    assert.equal((await request(foreign, "operations/" + op.id)).status, 404);
    for (const action of ["rollout", "pause", "resume", "rollback", "kill"])
      assert.equal(
        (
          await request(foreign, `releases/${id}/${action}`, "POST", {
            version: 1,
            percentage: 100,
          })
        ).status,
        404,
      );
    await wait(a, op);
    let release = (await request(a, "releases/" + id)).body;
    assert.equal(release.status, "ready");
    async function control(action, extra = {}) {
      release = (await request(a, "releases/" + id)).body;
      const r = await request(a, `releases/${id}/${action}`, "POST", {
        version: release.version,
        ...extra,
      });
      assert.equal(r.status, 200, JSON.stringify(r.body));
      return r.body;
    }
    await control("rollout", { percentage: 25 });
    await control("pause");
    assert.equal(
      (
        await request(a, `releases/${id}/rollout`, "POST", {
          version: 1,
          percentage: 100,
        })
      ).status,
      409,
    );
    await control("resume");
    await control("rollout", { percentage: 100 });
    const kill = await control("kill", { reason: "integration test" });
    await wait(a, kill);
    release = (await request(a, "releases/" + id)).body;
    assert.equal(release.status, "killed");
    assert.equal(
      (
        await request(a, `releases/${id}/resume`, "POST", {
          version: release.version,
        })
      ).status,
      409,
    );
    const h = {
      "expo-app-id": a.app,
      "expo-channel-name": "development",
      "expo-runtime-version": artifact.runtimeVersion,
      "expo-platform": "android",
      "expo-protocol-version": "1",
      "eas-client-id": randomUUID(),
      "expo-embedded-update-id": randomUUID(),
      "expo-expect-signature": 'sig, keyid="main", alg="rsa-v1_5-sha256"',
    };
    const response = await fetch(base + "/manifest", { headers: h });
    assert.equal(response.status, 200);
    const raw = await response.text();
    assert(raw.includes("rollBackToEmbedded"));
    assert(raw.includes("expo-signature:"));
    const unauthorized = await request(foreign, "releases", "POST", {
      ...body,
      environmentId: a.environment,
    });
    assert.equal(unauthorized.status, 404);
  },
);
