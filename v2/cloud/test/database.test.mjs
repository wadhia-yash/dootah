import { test, after } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { pool, sha, secret, limit } from "../core.mjs";
const enabled =
  (process.env.DOOTAH_CLOUD_DATABASE_URL || process.env.DOOTAH_CLOUD_DATABASE_URL_FILE) && process.env.DOOTAH_TEST_TENANTS;
const tenants = enabled
  ? JSON.parse(await readFile(process.env.DOOTAH_TEST_TENANTS))
  : null;
after(() => pool.end());
test(
  "database constraints reject cross-tenant parents and runtime cannot mutate audit or upstream data",
  { skip: !enabled },
  async () => {
    const { a, b } = tenants;
    await assert.rejects(
      pool.query(
        "INSERT INTO environments(id,organization_id,app_id,name,runtime,contract) VALUES($1,$2,$3,$4,$5,$6)",
        [randomUUID(), a.org, b.app, "cross", "test", {}],
      ),
      (e) => e.code === "23503",
    );
    await assert.rejects(
      pool.query("UPDATE audit SET action=$2 WHERE organization_id=$1", [
        a.org,
        "tampered",
      ]),
      (e) => e.code === "42501",
    );
    await assert.rejects(
      pool.query("DELETE FROM audit WHERE organization_id=$1", [a.org]),
      (e) => e.code === "42501",
    );
    await assert.rejects(
      pool.query("TRUNCATE audit"),
      (e) => e.code === "42501",
    );
    const {
      rows: [r],
    } = await pool.query(
      "SELECT rolsuper,rolbypassrls,rolcreatedb,rolcreaterole FROM pg_roles WHERE rolname=current_user",
    );
    assert(Object.values(r).every((v) => v === false));
    await assert.rejects(
      pool.query("CREATE TABLE dootah_cloud.unexpected(id int)"),
      (e) => e.code === "42501",
    );
  },
);
test(
  "expired credentials are rejected and token secrets are stored only as digests",
  { skip: !enabled },
  async () => {
    const a = tenants.a,
      raw = secret(),
      id = randomUUID();
    const {
      rows: [member],
    } = await pool.query(
      "SELECT user_id FROM members WHERE organization_id=$1 AND role=$2 LIMIT 1",
      [a.org, "Owner"],
    );
    await pool.query(
      "INSERT INTO credentials(id,digest,kind,user_id,organization_id,scopes,expires_at) VALUES($1,$2,'api',$3,$4,$5,now()-interval '1 second')",
      [id, sha(raw), member.user_id, a.org, ["app:read"]],
    );
    const response = await fetch(
      (process.env.DOOTAH_TEST_ORIGIN ?? "http://127.0.0.1:3100") + "/v1/apps",
      { headers: { Authorization: `Bearer ${raw}` } },
    );
    assert.equal(response.status, 401);
    const {
      rows: [record],
    } = await pool.query(
      "SELECT * FROM credentials WHERE organization_id=$1 AND id=$2",
      [a.org, id],
    );
    assert.equal(record.digest, sha(raw));
    assert(!JSON.stringify(record).includes(raw));
  },
);
test(
  "shared PostgreSQL limiter persists across callers",
  { skip: !enabled },
  async () => {
    const key = "test:" + randomUUID();
    await limit(pool, key, 2);
    await limit(pool, key, 2);
    await assert.rejects(limit(pool, key, 2), (e) => e.status === 429);
  },
);
test(
  "database cohort agrees exactly with the HTTP rollout algorithm",
  { skip: !enabled },
  async () => {
    const { bucket } = await import("../core.mjs");
    const release = randomUUID(),
      ids = Array.from({ length: 100 }, () => randomUUID());
    const { rows } = await pool.query(
      "SELECT id,rollout_bucket(id,$2) AS bucket FROM unnest($1::uuid[]) AS id",
      [ids, release],
    );
    for (const r of rows) assert.equal(r.bucket, bucket(r.id, release));
  },
);
