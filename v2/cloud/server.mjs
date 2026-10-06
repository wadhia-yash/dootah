import http from "node:http";
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import {
  pool,
  tx,
  sha,
  secret,
  checkPassword,
  password,
  Fault,
  requireValue,
  uuid,
  str,
  fields,
  scopes,
  permit,
  owner,
  owned,
  audit,
  limit,
  eligible,
} from "./core.mjs";
import { validate, binding, manifest } from "./adapter.mjs";
import { work } from "./worker.mjs";
import { configuration, productionReady } from "./config.mjs";
import { clientAddress } from "./client.mjs";
const settings = configuration();
const origin = settings.origin;
const local = settings.local;
let readyUntil = 0, readyCheck;
async function ready() {
  if (Date.now() < readyUntil) return;
  if (!readyCheck) readyCheck = productionReady(pool).then(() => { readyUntil = Date.now() + 5000; }).finally(() => { readyCheck = null; });
  try { await readyCheck; } catch { throw new Fault(503, "not_ready"); }
}
const dummyPassword = password("unmatchable-login-password");
const clean = (row) => {
  const {
    digest,
    password_hash,
    delivery_key,
    artifact,
    artifact_bytes,
    branch,
    upstream_id,
    upstream_uuid,
    directive_branch,
    key,
    ...rest
  } = row;
  if (rest.receipt)
    rest.receipt = {
      signatureVerified: rest.receipt.signatureVerified === true,
      type: rest.receipt.type ?? "release",
    };
  return rest;
};
async function body(req, max = 131072) {
  let bytes = 0,
    parts = [];
  for await (const chunk of req) {
    bytes += chunk.length;
    requireValue(bytes <= max, "body_too_large", 413);
    parts.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(parts).toString("utf8") || "{}");
  } catch {
    throw new Fault(422, "invalid_json");
  }
}
async function auth(req, db) {
  const raw =
    req.headers.authorization?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1] ??
    req.headers.cookie?.match(/(?:^|; )dootah=([A-Za-z0-9_-]{43})(?:;|$)/)?.[1];
  requireValue(raw, "unauthenticated", 401);
  const {
    rows: [a],
  } = await db.query(
    "SELECT * FROM credentials WHERE digest=$1 AND revoked_at IS NULL AND expires_at>now()",
    [sha(raw)],
  );
  requireValue(a, "unauthenticated", 401);
  if (a.kind === "session") {
    if (!["GET", "HEAD"].includes(req.method)) {
      requireValue(
        req.headers.origin === origin &&
          req.headers["x-csrf-token"] === sha(raw + "csrf"),
        "csrf",
        403,
      );
    }
    a.organization_id = req.headers["dootah-organization"];
    a.scopes = scopes;
  } else if (req.headers["dootah-organization"])
    requireValue(
      a.organization_id === req.headers["dootah-organization"],
      "not_found",
      404,
    );
  if (a.user_id && a.organization_id) {
    requireValue(uuid(a.organization_id));
    const {
      rows: [member],
    } = await db.query(
      "SELECT role FROM members WHERE organization_id=$1 AND user_id=$2",
      [a.organization_id, a.user_id],
    );
    requireValue(member, "not_found", 404);
    a.role = member.role;
  }
  return a;
}
async function mint(db, data, minutes) {
  const raw = secret(),
    id = randomUUID();
  await db.query(
    "INSERT INTO credentials(id,digest,kind,user_id,organization_id,app_id,environment_id,installation_id,scopes,expires_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,now()+$10*interval '1 minute')",
    [
      id,
      sha(raw),
      data.kind,
      data.user_id ?? null,
      data.organization_id ?? null,
      data.app_id ?? null,
      data.environment_id ?? null,
      data.installation_id ?? null,
      data.scopes ?? [],
      minutes,
    ],
  );
  return { id, secret: raw };
}
async function operation(db, a, r, action, key, digest) {
  str(key, 128);
  const {
    rows: [old],
  } = await db.query(
    "SELECT * FROM operations WHERE organization_id=$1 AND key=$2",
    [a.organization_id, key],
  );
  if (old) {
    requireValue(
      old.digest === digest &&
        old.resource_id === r.id &&
        old.action === action,
      "idempotency_conflict",
      409,
    );
    return clean(old);
  }
  const {
    rows: [pending],
  } = await db.query(
    "SELECT o.id FROM operations o JOIN releases r ON r.organization_id=o.organization_id AND r.id=o.resource_id WHERE o.organization_id=$1 AND r.environment_id=$2 AND o.status IN ('pending','running')",
    [a.organization_id, r.environment_id],
  );
  requireValue(!pending, "operation_pending", 409);
  const {
    rows: [op],
  } = await db.query(
    "INSERT INTO operations(id,organization_id,resource_id,actor,key,digest,action,status) VALUES($1,$2,$3,$4,$5,$6,$7,'pending') RETURNING *",
    [randomUUID(), a.organization_id, r.id, a.user_id, key, digest, action],
  );
  await audit(db, a, `${action}.requested`, r.id, { operationId: op.id });
  return clean(op);
}
async function list(db, a, table, url) {
  const size = Number(url.searchParams.get("limit") ?? 25);
  requireValue(Number.isInteger(size) && size >= 1 && size <= 100);
  let values = [a.organization_id],
    where = "organization_id=$1";
  if (table === "credentials") where += " AND kind='api'";
  if (
    a.app_id &&
    ["apps", "environments", "releases", "installations", "events"].includes(
      table,
    )
  ) {
    values.push(a.app_id);
    where += ` AND ${table === "apps" ? "id" : "app_id"}=$${values.length}`;
  }
  if (
    a.environment_id &&
    ["environments", "releases", "installations", "events"].includes(table)
  ) {
    values.push(a.environment_id);
    where += ` AND ${table === "environments" ? "id" : "environment_id"}=$${values.length}`;
  }
  for (const [field, column] of [
    ["appId", "app_id"],
    ["environmentId", "environment_id"],
    ["releaseId", "release_id"],
  ]) {
    if (url.searchParams.has(field)) {
      requireValue(
        (field === "appId" &&
          ["environments", "releases", "installations", "events"].includes(
            table,
          )) ||
          (field === "environmentId" &&
            ["releases", "installations", "events"].includes(table)) ||
          (field === "releaseId" && table === "events"),
      );
      const value = url.searchParams.get(field);
      requireValue(uuid(value));
      values.push(value);
      where += ` AND ${column}=$${values.length}`;
    }
  }
  if (url.searchParams.has("after")) {
    const after = url.searchParams.get("after");
    if (table === "events") {
      const parts = after.split(":");
      requireValue(parts.length === 2 && parts.every(uuid));
      values.push(...parts);
      where += ` AND (id,installation_id)>($${values.length - 1},$${values.length})`;
    } else {
      requireValue(table === "audit" ? /^\d+$/.test(after) : uuid(after));
      values.push(after);
      where += ` AND id>$${values.length}`;
    }
  }
  values.push(size + 1);
  const { rows } = await db.query(
    `SELECT * FROM ${table} WHERE ${where} ORDER BY id${table === "events" ? ",installation_id" : ""} LIMIT $${values.length}`,
    values,
  );
  return {
    items: rows.slice(0, size).map(clean),
    nextCursor:
      rows.length > size
        ? String(rows[size - 1].id) +
          (table === "events" ? ":" + rows[size - 1].installation_id : "")
        : null,
  };
}
export async function metrics(db, a, r) {
  const {
    rows: [population],
  } = await db.query(
    `SELECT count(*) AS compatible,
 count(*) FILTER(WHERE rollout_bucket(id,$4)<$5*100) AS eligible,
 count(*) FILTER(WHERE rollout_bucket(id,$4)<$5*100 AND active_release=$4) AS adopted
 FROM installations WHERE organization_id=$1 AND environment_id=$2 AND runtime=$3
 AND last_seen>now()-interval '30 days'`,
    [a.organization_id, r.environment_id, r.runtime, r.id, r.percentage],
  );
  const { rows: counts } = await db.query(
    `SELECT type,count(DISTINCT installation_id) AS count FROM events
 WHERE organization_id=$1 AND release_id=$2 AND occurred_at>now()-interval '30 days' GROUP BY type`,
    [a.organization_id, r.id],
  );
  const count = (type) =>
    Number(counts.find((r) => r.type === type)?.count ?? 0);
  const {
    rows: [health],
  } = await db.query(
    `SELECT count(DISTINCT a.installation_id) AS healthy FROM events a
 WHERE a.organization_id=$1 AND a.release_id=$2 AND a.type='update_activated' AND a.occurred_at>now()-interval '30 days'
 AND EXISTS(SELECT 1 FROM events h WHERE h.organization_id=a.organization_id AND h.release_id=a.release_id
 AND h.installation_id=a.installation_id AND h.activation_id=a.activation_id AND h.type='update_healthy' AND h.occurred_at>=a.occurred_at)`,
    [a.organization_id, r.id],
  );
  const eligible = Number(population.eligible),
    adopted = Number(population.adopted),
    healthy = Number(health.healthy),
    activated = count("update_activated");
  return {
    windowDays: 30,
    asOf: new Date().toISOString(),
    compatible: Number(population.compatible),
    eligible,
    downloaded: count("download_completed"),
    activated,
    healthy,
    rejected: count("update_rejected"),
    rollbackCount: count("rollback_applied"),
    adopted,
    adoptionPercent: eligible ? (100 * adopted) / eligible : null,
    healthPercent: activated ? (100 * healthy) / activated : null,
    provenance: "authenticated_device_reports",
    crashFree: null,
  };
}
const eventTypes = [
  "update_checked",
  "update_available",
  "download_started",
  "download_completed",
  "verification_failed",
  "update_activated",
  "update_healthy",
  "update_rejected",
  "rollback_applied",
  "native_fallback",
];
async function ingest(db, a, b) {
  requireValue(a.kind === "installation", "installation_auth_required", 403);
  fields(b, ["events"]);
  requireValue(
    Array.isArray(b.events) &&
      b.events.length > 0 &&
      b.events.length <= 32 &&
      Buffer.byteLength(JSON.stringify(b)) <= 32768,
  );
  await limit(pool, `events:${a.id}`, 120, b.events.length);
  await limit(pool, `events-org:${a.organization_id}`, 10000, b.events.length);
  const installation = await owned(db, a, "installations", a.installation_id);
  for (const e of b.events) {
    fields(e, [
      "id",
      "installationId",
      "updateId",
      "type",
      "activationId",
      "timestamp",
      "runtimeAbi",
      "logicAbi",
      "metadata",
    ]);
    requireValue(
      uuid(e.id) &&
        e.installationId === installation.id &&
        uuid(e.activationId) &&
        eventTypes.includes(e.type) &&
        e.runtimeAbi === 2 &&
        e.logicAbi === 1,
    );
    fields(e.metadata, []);
    const time = new Date(str(e.timestamp, 40));
    requireValue(
      Number.isFinite(+time) &&
        +time > Date.now() - 7 * 86400000 &&
        +time < Date.now() + 300000,
    );
    let release = null;
    if (e.updateId !== null) {
      requireValue(uuid(e.updateId));
      const {
        rows: [r],
      } = await db.query(
        "SELECT * FROM releases WHERE organization_id=$1 AND app_id=$2 AND environment_id=$3 AND upstream_uuid=$4",
        [a.organization_id, a.app_id, a.environment_id, e.updateId],
      );
      release = r;
      if (!r)
        requireValue(
          e.updateId === installation.embedded_id,
          "unknown_release",
          422,
        );
    }
    if (
      [
        "update_available",
        "download_started",
        "download_completed",
        "update_rejected",
      ].includes(e.type)
    )
      requireValue(release, "release_required");
    const hash = sha(JSON.stringify(e));
    const {
      rows: [old],
    } = await db.query(
      "SELECT digest FROM events WHERE organization_id=$1 AND installation_id=$2 AND id=$3",
      [a.organization_id, installation.id, e.id],
    );
    if (old) {
      requireValue(old.digest === hash, "event_conflict", 409);
      continue;
    }
    await db.query(
      "INSERT INTO events(id,organization_id,app_id,environment_id,installation_id,release_id,type,activation_id,occurred_at,digest,metadata) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)",
      [
        e.id,
        a.organization_id,
        a.app_id,
        a.environment_id,
        installation.id,
        release?.id ?? null,
        e.type,
        e.activationId,
        time,
        hash,
        e.metadata,
      ],
    );
    if (e.type === "update_activated")
      await db.query(
        "UPDATE installations SET active_release=$3,active_at=$4 WHERE organization_id=$1 AND id=$2 AND (active_at IS NULL OR active_at<$4)",
        [a.organization_id, installation.id, release?.id ?? null, time],
      );
  }
  await db.query(
    "UPDATE installations SET last_seen=now() WHERE organization_id=$1 AND id=$2",
    [a.organization_id, installation.id],
  );
  return { accepted: b.events.length };
}
async function api(req, res, url, b, client) {
  return tx(async (db) => {
    const p = url.pathname,
      method = req.method;
    if (p === "/v1/sessions" && method === "POST") {
      fields(b, ["email", "password"]);
      str(b.email);
      str(b.password, 256);
      requireValue(req.headers.origin === origin, "origin", 403);
      await limit(pool, `login-ip:${client}`, 20);
      await limit(pool, `login:${b.email}`, 10);
      const {
        rows: [user],
      } = await db.query("SELECT * FROM users WHERE email=$1", [b.email]);
      requireValue(
        checkPassword(b.password, user?.password_hash ?? dummyPassword) && user,
        "unauthenticated",
        401,
      );
      const token = await mint(db, { kind: "session", user_id: user.id }, 480);
      res.setHeader(
        "Set-Cookie",
        `dootah=${token.secret}; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800${local ? "" : "; Secure"}`,
      );
      return { csrf: sha(token.secret + "csrf"), userId: user.id };
    }
    const a = await auth(req, db);
    await limit(pool, `api:${a.id}`, a.kind === "installation" ? 180 : 300);
    if (p === "/v1/sessions/current" && method === "DELETE") {
      await db.query("UPDATE credentials SET revoked_at=now() WHERE id=$1", [
        a.id,
      ]);
      res.setHeader(
        "Set-Cookie",
        "dootah=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0",
      );
      return { revoked: true };
    }
    if (p === "/v1/organizations") {
      requireValue(a.kind === "session", "session_required", 403);
      if (method === "GET")
        return {
          items: (
            await db.query(
              "SELECT o.*,m.role FROM organizations o JOIN members m ON m.organization_id=o.id WHERE m.user_id=$1 ORDER BY o.id LIMIT 100",
              [a.user_id],
            )
          ).rows,
        };
      if (method === "POST") {
        fields(b, ["name"]);
        const id = randomUUID();
        await db.query("INSERT INTO organizations(id,name) VALUES($1,$2)", [
          id,
          str(b.name),
        ]);
        await db.query("INSERT INTO members VALUES($1,$2,'Owner')", [
          id,
          a.user_id,
        ]);
        await audit(
          db,
          { ...a, organization_id: id },
          "organization.created",
          id,
        );
        return { id };
      }
    }
    requireValue(uuid(a.organization_id), "organization_required", 403);
    if (p === "/v1/enroll" && method === "POST") {
      requireValue(a.kind === "enrollment", "enrollment_required", 403);
      fields(b, [
        "installationId",
        "runtime",
        "nativeVersion",
        "sdkVersion",
        "embeddedId",
      ]);
      requireValue(uuid(b.installationId) && uuid(b.embeddedId));
      const env = await owned(db, a, "environments", a.environment_id);
      requireValue(b.runtime === env.runtime);
      const {
        rows: [existing],
      } = await db.query(
        "SELECT * FROM installations WHERE organization_id=$1 AND id=$2 FOR UPDATE",
        [a.organization_id, b.installationId],
      );
      requireValue(
        !existing ||
          (existing.app_id === a.app_id &&
            existing.environment_id === a.environment_id),
        "not_found",
        404,
      );
      const { rowCount } = await db.query(
        "UPDATE credentials SET revoked_at=now() WHERE id=$1 AND organization_id=$2 AND revoked_at IS NULL",
        [a.id, a.organization_id],
      );
      requireValue(rowCount === 1, "enrollment_consumed", 409);
      await db.query(
        "INSERT INTO installations(id,organization_id,app_id,environment_id,runtime,native_version,sdk_version,embedded_id) VALUES($1,$2,$3,$4,$5,$6,$7,$8) ON CONFLICT(organization_id,id) DO UPDATE SET native_version=$6,sdk_version=$7,embedded_id=$8,last_seen=now()",
        [
          b.installationId,
          a.organization_id,
          a.app_id,
          a.environment_id,
          b.runtime,
          str(b.nativeVersion),
          str(b.sdkVersion),
          b.embeddedId,
        ],
      );
      await db.query(
        "UPDATE credentials SET revoked_at=now() WHERE organization_id=$1 AND installation_id=$2 AND kind='installation'",
        [a.organization_id, b.installationId],
      );
      const token = await mint(
        db,
        {
          kind: "installation",
          organization_id: a.organization_id,
          app_id: a.app_id,
          environment_id: a.environment_id,
          installation_id: b.installationId,
        },
        43200,
      );
      return { credential: token.secret, installationId: b.installationId };
    }
    if (p === "/v1/events" && method === "POST") return ingest(db, a, b);
    requireValue(a.user_id && a.role, "user_required", 403);
    if (p === "/v1/enrollment-tickets" && method === "POST") {
      permit(a, "release:publish");
      fields(b, ["environmentId"]);
      const env = await owned(db, a, "environments", b.environmentId);
      const token = await mint(
        db,
        {
          kind: "enrollment",
          organization_id: a.organization_id,
          app_id: env.app_id,
          environment_id: env.id,
        },
        10,
      );
      await audit(db, a, "enrollment.created", token.id);
      return token;
    }
    if (p === "/v1/members") {
      owner(a);
      if (method === "GET")
        return {
          items: (
            await db.query(
              "SELECT m.user_id,m.role,u.email FROM members m JOIN users u ON u.id=m.user_id WHERE organization_id=$1 ORDER BY m.user_id LIMIT 100",
              [a.organization_id],
            )
          ).rows,
        };
      if (method === "POST") {
        fields(b, ["email", "role"]);
        requireValue(["Owner", "Developer", "Viewer"].includes(b.role));
        const {
          rows: [u],
        } = await db.query("SELECT id FROM users WHERE email=$1", [
          str(b.email),
        ]);
        requireValue(u, "user_not_provisioned", 422);
        await db.query("INSERT INTO members VALUES($1,$2,$3)", [
          a.organization_id,
          u.id,
          b.role,
        ]);
        await audit(db, a, "member.added", u.id, { role: b.role });
        return { id: u.id };
      }
    }
    if (p.startsWith("/v1/members/") && ["PATCH", "DELETE"].includes(method)) {
      owner(a);
      const id = p.split("/")[3];
      requireValue(uuid(id));
      await db.query("SELECT id FROM organizations WHERE id=$1 FOR UPDATE", [
        a.organization_id,
      ]);
      const {
        rows: [m],
      } = await db.query(
        "SELECT * FROM members WHERE organization_id=$1 AND user_id=$2",
        [a.organization_id, id],
      );
      requireValue(m, "not_found", 404);
      if (method === "PATCH") {
        fields(b, ["role"]);
        requireValue(["Owner", "Developer", "Viewer"].includes(b.role));
      }
      if (m.role === "Owner" && (method === "DELETE" || b.role !== "Owner")) {
        const {
          rows: [n],
        } = await db.query(
          "SELECT count(*) FROM members WHERE organization_id=$1 AND role='Owner'",
          [a.organization_id],
        );
        requireValue(Number(n.count) > 1, "last_owner", 409);
      }
      if (method === "DELETE")
        await db.query(
          "DELETE FROM members WHERE organization_id=$1 AND user_id=$2",
          [a.organization_id, id],
        );
      else
        await db.query(
          "UPDATE members SET role=$3 WHERE organization_id=$1 AND user_id=$2",
          [a.organization_id, id, b.role],
        );
      await audit(db, a, "member.changed", id, { role: b.role ?? "removed" });
      return { id };
    }
    if (p === "/v1/tokens") {
      owner(a);
      if (method === "GET") return list(db, a, "credentials", url);
      if (method === "POST") {
        fields(
          b,
          ["scopes", "expiresDays", "appId", "environmentId"],
          ["scopes", "expiresDays"],
        );
        requireValue(
          Array.isArray(b.scopes) &&
            b.scopes.length > 0 &&
            b.scopes.length <= scopes.length &&
            new Set(b.scopes).size === b.scopes.length &&
            b.scopes.every((s) => scopes.includes(s)),
        );
        requireValue(
          Number.isInteger(b.expiresDays) &&
            b.expiresDays >= 1 &&
            b.expiresDays <= 90,
        );
        if (b.appId) await owned(db, a, "apps", b.appId);
        if (b.environmentId) {
          const env = await owned(db, a, "environments", b.environmentId);
          requireValue(env.app_id === b.appId);
        }
        const token = await mint(
          db,
          {
            kind: "api",
            user_id: a.user_id,
            organization_id: a.organization_id,
            app_id: b.appId,
            environment_id: b.environmentId,
            scopes: b.scopes,
          },
          b.expiresDays * 1440,
        );
        await audit(db, a, "token.created", token.id, { scopes: b.scopes });
        return token;
      }
    }
    if (p.startsWith("/v1/tokens/") && method === "DELETE") {
      owner(a);
      const token = await owned(db, a, "credentials", p.split("/")[3]);
      await db.query(
        "UPDATE credentials SET revoked_at=now() WHERE organization_id=$1 AND id=$2",
        [a.organization_id, token.id],
      );
      await audit(db, a, "token.revoked", token.id);
      return { revoked: true };
    }
    const table = p.split("/")[2],
      id = p.split("/")[3],
      action = p.split("/")[4];
    const readScopes = {
      apps: "app:read",
      environments: "app:read",
      releases: "release:read",
      installations: "telemetry:read",
      events: "telemetry:read",
      audit: "audit:read",
      operations: "release:read",
    };
    if (method === "GET" && readScopes[table]) {
      permit(a, readScopes[table]);
      if (table === "audit")
        requireValue(
          !a.app_id && !a.environment_id,
          "organization_scope_required",
          403,
        );
      if (table === "operations") {
        const op = await owned(
          db,
          { ...a, app_id: null, environment_id: null },
          "operations",
          id,
        );
        await owned(db, a, "releases", op.resource_id);
        return clean(op);
      }
      if (!id) return list(db, a, table, url);
      const r = await owned(db, a, table, id);
      return {
        ...clean(r),
        ...(table === "releases"
          ? {
              metrics: await metrics(db, a, r),
              operation:
                (
                  await db.query(
                    "SELECT id,action,status,created_at FROM operations WHERE organization_id=$1 AND resource_id=$2 ORDER BY created_at DESC LIMIT 1",
                    [a.organization_id, r.id],
                  )
                ).rows[0] ?? null,
            }
          : {}),
      };
    }
    if (table === "apps" && !id && method === "POST") {
      owner(a);
      fields(b, ["name"]);
      const id = randomUUID();
      await db.query(
        "INSERT INTO apps(id,organization_id,name,delivery_key) VALUES($1,$2,$3,$4)",
        [id, a.organization_id, str(b.name), id],
      );
      await audit(db, a, "app.created", id);
      return { id };
    }
    if (table === "environments" && !id && method === "POST") {
      owner(a);
      fields(b, ["appId", "name", "runtime", "contract"]);
      await owned(db, a, "apps", b.appId);
      requireValue(
        b.contract &&
          Object.keys(b.contract).length === 2 &&
          b.contract.capabilities &&
          b.contract.functions,
      );
      const id = randomUUID();
      await db.query(
        "INSERT INTO environments(id,organization_id,app_id,name,runtime,contract) VALUES($1,$2,$3,$4,$5,$6)",
        [
          id,
          a.organization_id,
          b.appId,
          str(b.name),
          str(b.runtime),
          b.contract,
        ],
      );
      await audit(db, a, "environment.created", id);
      return { id };
    }
    if (table === "releases" && !id && method === "POST") {
      permit(a, "release:publish");
      fields(b, ["environmentId", "revision", "artifact"]);
      const env = await owned(db, a, "environments", b.environmentId, true);
      const key = str(req.headers["idempotency-key"], 128),
        digest = sha(JSON.stringify(b));
      const {
        rows: [old],
      } = await db.query(
        "SELECT * FROM operations WHERE organization_id=$1 AND key=$2",
        [a.organization_id, key],
      );
      if (old) {
        requireValue(
          old.digest === digest && old.action === "publish",
          "idempotency_conflict",
          409,
        );
        await owned(db, a, "releases", old.resource_id);
        return clean(old);
      }
      await limit(db, `publish-org:${a.organization_id}`, 30);
      await validate(b.artifact, env.runtime, env.contract);
      const id = randomUUID();
      const {
        rows: [r],
      } = await db.query(
        "INSERT INTO releases(id,organization_id,app_id,environment_id,runtime,runtime_abi,logic_abi,revision,hash,artifact,creator,status,percentage,branch,contract_digest) VALUES($1,$2,$3,$4,$5,2,1,$6,$7,$8,$9,'draft',0,$10,(SELECT encode(sha256(convert_to(contract::text,'UTF8')),'hex') FROM environments WHERE organization_id=$2 AND id=$4)) RETURNING *",
        [
          id,
          a.organization_id,
          env.app_id,
          env.id,
          env.runtime,
          str(b.revision, 256),
          sha(JSON.stringify(b.artifact)),
          b.artifact,
          a.user_id,
          `cloud-${id}`,
        ],
      );
      await db.query(
        "UPDATE releases SET artifact_bytes=$3 WHERE organization_id=$1 AND id=$2",
        [a.organization_id, r.id, JSON.stringify(b.artifact)],
      );
      return operation(db, a, r, "publish", key, digest);
    }
    if (table === "releases" && id && method === "POST") {
      permit(a, "release:control");
      const r = await owned(db, a, "releases", id);
      const env = await owned(db, a, "environments", r.environment_id, true);
      const current = await owned(db, a, "releases", id, true);
      fields(b, ["version", "percentage", "reason"], ["version"]);
      if (["rollback", "kill"].includes(action)) {
        const {
          rows: [prior],
        } = await db.query(
          "SELECT * FROM operations WHERE organization_id=$1 AND key=$2",
          [a.organization_id, req.headers["idempotency-key"]],
        );
        if (prior) {
          requireValue(
            prior.resource_id === id &&
              prior.action === action &&
              prior.digest === sha(JSON.stringify(b)),
            "idempotency_conflict",
            409,
          );
          return clean(prior);
        }
      }
      requireValue(b.version === current.version, "stale_version", 409);
      requireValue(
        !["draft", "failed", "killed", "rolled_back"].includes(current.status),
        "invalid_state",
        409,
      );
      const {
        rows: [pending],
      } = await db.query(
        "SELECT o.id FROM operations o JOIN releases r ON r.organization_id=o.organization_id AND r.id=o.resource_id WHERE o.organization_id=$1 AND r.environment_id=$2 AND o.status IN ('pending','running')",
        [a.organization_id, env.id],
      );
      requireValue(!pending, "operation_pending", 409);
      if (["rollback", "kill"].includes(action)) {
        requireValue(env.head === id, "not_environment_head", 409);
        if (b.reason !== undefined) str(b.reason, 256);
        const op = await operation(
          db,
          a,
          r,
          action,
          str(req.headers["idempotency-key"]),
          sha(JSON.stringify(b)),
        );
        await audit(db, a, `${action}.reason`, id, { reason: b.reason ?? "" });
        return op;
      }
      let status = current.status,
        percentage = current.percentage;
      if (action === "rollout") {
        requireValue(
          Number.isInteger(b.percentage) &&
            b.percentage >= 0 &&
            b.percentage <= 100,
        );
        requireValue(current.status !== "paused", "resume_required", 409);
        percentage = b.percentage;
        status = percentage === 100 ? "active" : "rolling_out";
        if (env.head && env.head !== id) {
          await db.query(
            "UPDATE releases SET status='paused',version=version+1 WHERE organization_id=$1 AND id=$2 AND status IN ('active','rolling_out')",
            [a.organization_id, env.head],
          );
        }
        await db.query(
          "UPDATE environments SET head=$3,directive_branch=NULL WHERE organization_id=$1 AND id=$2",
          [a.organization_id, env.id, id],
        );
      } else if (action === "pause") {
        requireValue(
          ["active", "rolling_out"].includes(status),
          "invalid_state",
          409,
        );
        status = "paused";
      } else if (action === "resume") {
        requireValue(
          status === "paused" && env.head === id,
          "invalid_state",
          409,
        );
        status = percentage === 100 ? "active" : "rolling_out";
      } else throw new Fault(404, "not_found");
      await db.query(
        "UPDATE releases SET status=$3,percentage=$4,version=version+1 WHERE organization_id=$1 AND id=$2",
        [a.organization_id, id, status, percentage],
      );
      await audit(db, a, action, id, { percentage });
      return { id, status, percentage, version: current.version + 1 };
    }
    throw new Fault(404, "not_found");
  });
}
async function delivery(req, res, url, client) {
  const app = req.headers["expo-app-id"],
    installation = req.headers["eas-client-id"];
  requireValue(uuid(app) && uuid(installation), "not_found", 404);
  await limit(pool, `delivery:${client}`, 600);
  const {
    rows: [env],
  } = await pool.query(
    "SELECT e.* FROM environments e JOIN apps a ON a.organization_id=e.organization_id AND a.id=e.app_id WHERE a.id=$1 AND e.name=$2 AND e.runtime=$3",
    [
      app,
      req.headers["expo-channel-name"],
      req.headers["expo-runtime-version"],
    ],
  );
  requireValue(env, "not_found", 404);
  const c = await binding(app),
    headers = {};
  for (const h of [
    "accept",
    "expo-protocol-version",
    "expo-expect-signature",
    "expo-current-update-id",
    "expo-embedded-update-id",
    "expo-recent-failed-update-ids",
    "expo-requested-update-id",
    "eas-client-id",
  ])
    if (req.headers[h]) headers[h] = req.headers[h];
  let response;
  if (url.pathname === "/manifest") {
    let branch = env.directive_branch;
    if (!branch && env.head) {
      const {
        rows: [r],
      } = await pool.query(
        "SELECT * FROM releases WHERE organization_id=$1 AND id=$2",
        [env.organization_id, env.head],
      );
      if (
        r &&
        ["active", "rolling_out"].includes(r.status) &&
        eligible(installation, r.id, r.percentage)
      ) {
        branch = r.branch;
        await pool.query(
          "INSERT INTO grants VALUES($1,$2,$3) ON CONFLICT DO NOTHING",
          [env.organization_id, installation, r.id],
        );
      }
    }
    if (!branch) {
      res.writeHead(204);
      res.end();
      return;
    }
    response = await manifest(app, branch, env.runtime, headers);
  } else {
    // Bind an asset to an actual offer and the same app/environment. Ignore caller-supplied branch routing.
    const hash = url.searchParams.get("h");
    requireValue(
      typeof hash === "string" && /^[A-Za-z0-9_-]{43}$/.test(hash),
      "not_found",
      404,
    );
    const hex = Buffer.from(hash, "base64url").toString("hex");
    const {
      rows: [r],
    } = await pool.query(
      "SELECT r.* FROM releases r JOIN grants g ON g.organization_id=r.organization_id AND g.release_id=r.id WHERE r.organization_id=$1 AND r.app_id=$2 AND r.environment_id=$3 AND g.installation_id=$4 AND r.hash=$5 AND r.status NOT IN ('killed','failed')",
      [env.organization_id, app, env.id, installation, hex],
    );
    requireValue(r, "not_found", 404);
    const target = new URL("/assets", c.base);
    target.searchParams.set("h", hash);
    target.searchParams.set("ext", "bundle");
    target.searchParams.set("platform", "android");
    target.searchParams.set("runtimeVersion", r.runtime);
    response = await fetch(target, {
      redirect: "error",
      signal: AbortSignal.timeout(15000),
      headers: {
        ...headers,
        "expo-app-id": c.appId,
        "expo-channel-name": r.branch,
        "expo-platform": "android",
        "expo-runtime-version": r.runtime,
      },
    });
  }
  requireValue(
    ![301, 302, 303, 307, 308].includes(response.status),
    "unexpected_delivery_redirect",
    502,
  );
  const bytes = Buffer.from(await response.arrayBuffer());
  requireValue(bytes.length <= 131072, "delivery_body_limit", 502);
  for (const name of [
    "content-type",
    "expo-protocol-version",
    "expo-sfv-version",
    "expo-signature",
    "expo-manifest-filters",
  ]) {
    const value = response.headers.get(name);
    if (value) res.setHeader(name, value);
  }
  res.setHeader("Cache-Control", "private, no-store");
  res.writeHead(response.status);
  res.end(bytes);
}
export function createServer(trustedProxies = settings.trustedProxies) {
  return http.createServer(
    { maxHeaderSize: 16384, requestTimeout: 30000, headersTimeout: 10000 },
    async (req, res) => {
      res.setHeader("X-Content-Type-Options", "nosniff");
      res.setHeader("Referrer-Policy", "no-referrer");
      res.setHeader("Cache-Control", "no-store");
      res.setHeader(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
      );
      try {
        const url = new URL(req.url, origin);
        const client = clientAddress(req, trustedProxies);
        requireValue(req.url.length <= 2048);
        if (
          req.method === "GET" &&
          ["/manifest", "/assets"].includes(url.pathname)
        ) {
          await delivery(req, res, url, client);
          return;
        }
        if (
          req.method === "GET" &&
          ["/", "/dashboard.js", "/dashboard.css"].includes(url.pathname)
        ) {
          const file =
            url.pathname === "/" ? "dashboard.html" : url.pathname.slice(1);
          res.setHeader(
            "Content-Type",
            file.endsWith(".html")
              ? "text/html; charset=utf-8"
              : file.endsWith(".js")
                ? "text/javascript"
                : "text/css",
          );
          res.end(await readFile(new URL(file, import.meta.url)));
          return;
        }
        if (url.pathname === "/live" && req.method === "GET") {
          res.end("live"); return;
        }
        if (url.pathname === "/ready" && req.method === "GET") {
          if (settings.production) await ready();
          const {
            rows: [schema],
          } = await pool.query(
            "SELECT max(version) AS version FROM migrations",
          );
          requireValue(schema.version === 6, "migrations_required", 503);
          res.end("ready");
          return;
        }
        const b = ["POST", "PATCH", "PUT"].includes(req.method)
          ? await body(req)
          : {};
        const result = await api(req, res, url, b, client);
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify(result));
      } catch (error) {
        const status =
          error.status ?? (["23505", "23503"].includes(error.code) ? 409 : 500);
        res.statusCode = status;
        if (status === 429) res.setHeader("Retry-After", "60");
        res.setHeader("Content-Type", "application/json");
        res.end(
          JSON.stringify({
            error: {
              code:
                status === 500
                  ? "internal_error"
                  : error.status
                    ? error.message
                    : "conflict",
              message:
                status === 500
                  ? "Request failed"
                  : error.status
                    ? error.message
                    : "Resource conflict",
              requestId: randomUUID(),
            },
          }),
        );
        if (status === 500)
          console.error(JSON.stringify({event:"request_failed", code:"internal_error"}));
      }
    },
  );
}
if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  if (settings.production) {
    try { await productionReady(pool); }
    catch (e) { console.error(JSON.stringify({event:"startup_failed", message:e.message.startsWith("configuration:") ? e.message : "configuration unavailable"})); await pool.end(); process.exit(1); }
  }
  const server = createServer();
  server.listen(settings.port, settings.host, () =>
    console.log(JSON.stringify({event:"listening", mode:settings.production ? "production" : "development"})),
  );
  let busy = false;
  const timer = setInterval(async () => {
    if (busy) return;
    busy = true;
    try {
      await work();
    } catch (e) {
      console.error(JSON.stringify({event:"worker_unavailable"}));
    } finally {
      busy = false;
    }
  }, 3000);
  const cleanup = setInterval(
    () =>
      pool
        .query("DELETE FROM rate_limits WHERE window_id<$1", [
          Math.floor(Date.now() / 60000) - 60,
        ])
        .catch(() => {}),
    60000,
  );
  process.on("SIGTERM", () => {
    clearInterval(timer);
    clearInterval(cleanup);
    server.close(async () => {
      await pool.end();
      process.exit(0);
    });
  });
}
