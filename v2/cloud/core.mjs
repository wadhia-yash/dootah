import {
  createHash,
  randomBytes,
  scryptSync,
  timingSafeEqual,
} from "node:crypto";
import pg from "pg";
import { databaseURL } from "./config.mjs";
export const pool = new pg.Pool({
  connectionString: databaseURL,
  connectionTimeoutMillis: 5000,
  max: 10,
  options: "-c search_path=dootah_cloud,public -c statement_timeout=10000",
});
const ratePool = new pg.Pool({
  connectionString: databaseURL,
  connectionTimeoutMillis: 5000,
  max: 2,
  allowExitOnIdle: true,
  options: "-c search_path=dootah_cloud,public -c statement_timeout=3000",
});
// Broken idle connections must not terminate HTTP handling during a database restart.
for (const databasePool of [pool, ratePool])
  databasePool.on("error", () =>
    console.error(JSON.stringify({event:"database_unavailable"})),
  );
export const sha = (value) => createHash("sha256").update(value).digest("hex");
export const secret = () => randomBytes(32).toString("base64url");
export function password(value, salt = secret()) {
  return `${salt}:${scryptSync(value, salt, 64).toString("hex")}`;
}
export function checkPassword(value, stored) {
  const actual = password(value, stored.split(":")[0]);
  return (
    actual.length === stored.length &&
    timingSafeEqual(Buffer.from(actual), Buffer.from(stored))
  );
}
export class Fault extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
  }
}
export const requireValue = (
  condition,
  code = "invalid_input",
  status = 422,
) => {
  if (!condition) throw new Fault(status, code);
};
export const uuid = (value) =>
  typeof value === "string" &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
export function str(value, max = 128) {
  requireValue(
    typeof value === "string" &&
      value.length > 0 &&
      value.length <= max &&
      !/[\x00-\x1f]/.test(value),
  );
  return value;
}
export function fields(obj, allowed, required = allowed) {
  requireValue(
    obj &&
      typeof obj === "object" &&
      !Array.isArray(obj) &&
      Object.keys(obj).every((k) => allowed.includes(k)) &&
      required.every((k) => Object.hasOwn(obj, k)),
  );
}
export function bucket(installation, release) {
  requireValue(uuid(installation) && uuid(release));
  return Number(
    createHash("sha256")
      .update(`dootah-rollout-v1:${release}:${installation}`)
      .digest()
      .readBigUInt64BE() % 10000n,
  );
}
export const eligible = (installation, release, pct) =>
  bucket(installation, release) < pct * 100;
export async function tx(fn) {
  const db = await pool.connect();
  try {
    await db.query("BEGIN");
    const value = await fn(db);
    await db.query("COMMIT");
    return value;
  } catch (e) {
    await db.query("ROLLBACK");
    throw e;
  } finally {
    db.release();
  }
}
export async function audit(db, auth, action, resource, metadata = {}) {
  await db.query(
    "INSERT INTO audit(organization_id,actor,action,resource_id,metadata) VALUES($1,$2,$3,$4,$5)",
    [auth.organization_id, auth.user_id, action, resource, metadata],
  );
}
export async function limit(db, key, max, amount = 1) {
  db = ratePool;
  const window = Math.floor(Date.now() / 60000);
  const {
    rows: [r],
  } = await db.query(
    "INSERT INTO rate_limits VALUES($1,$2,$3) ON CONFLICT(key) DO UPDATE SET window_id=$2,count=CASE WHEN rate_limits.window_id=$2 THEN rate_limits.count+$3 ELSE $3 END RETURNING count",
    [sha(key), window, amount],
  );
  requireValue(r.count <= max, "rate_limited", 429);
}
export const scopes = [
  "app:read",
  "release:read",
  "release:publish",
  "release:control",
  "telemetry:read",
  "audit:read",
];
export function permit(auth, scope) {
  requireValue(
    auth.user_id &&
      auth.scopes.includes(scope) &&
      (auth.role !== "Viewer" || scope.endsWith(":read")),
    "forbidden",
    403,
  );
}
export function owner(auth) {
  requireValue(
    auth.kind === "session" && auth.role === "Owner",
    "owner_session_required",
    403,
  );
}
export async function owned(db, auth, table, id, lock = false) {
  requireValue(uuid(id), "not_found", 404);
  const {
    rows: [row],
  } = await db.query(
    `SELECT * FROM ${table} WHERE organization_id=$1 AND id=$2${lock ? " FOR UPDATE" : ""}`,
    [auth.organization_id, id],
  );
  requireValue(
    row &&
      (!auth.app_id ||
        (table === "apps" ? row.id : row.app_id) === auth.app_id) &&
      (!auth.environment_id ||
        table === "apps" ||
        (table === "environments" ? row.id : row.environment_id) ===
          auth.environment_id),
    "not_found",
    404,
  );
  return row;
}
