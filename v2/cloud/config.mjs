import { readFileSync, statSync, readdirSync } from "node:fs";
import { delimiter } from "node:path";
import { X509Certificate } from "node:crypto";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { trustedProxies } from "./client.mjs";

const fail = (name) => { throw Error(`configuration: ${name}`); };
export function secretInput(name, env = process.env) {
  if (env[name] && env[name + "_FILE"]) fail(name + " ambiguous input");
  if (!env[name + "_FILE"]) return env[name];
  try {
    const s = statSync(env[name + "_FILE"]);
    if (!s.isFile() || (s.mode & 0o077)) fail(name);
    return readFileSync(env[name + "_FILE"], "utf8").trimEnd();
  } catch { fail(name + "_FILE unreadable or not private"); }
}
export const databaseURL = secretInput("DOOTAH_CLOUD_DATABASE_URL");
export function configuration(env = process.env) {
  const mode = env.DOOTAH_MODE ?? "development";
  if (!["development", "production"].includes(mode)) fail("DOOTAH_MODE");
  if (env.NODE_ENV === "production" && env.DOOTAH_MODE !== "production") fail("DOOTAH_MODE must explicitly be production");
  const production = mode === "production";
  const origin = env.DOOTAH_CLOUD_ORIGIN ?? (production ? "" : "http://127.0.0.1:3100");
  let u;
  try { u = new URL(origin); } catch { fail("DOOTAH_CLOUD_ORIGIN"); }
  if (origin !== u.origin || u.username || u.password) fail("DOOTAH_CLOUD_ORIGIN must be an origin");
  if (production && env.DOOTAH_ALLOW_LOCAL_HTTP && env.DOOTAH_ALLOW_LOCAL_HTTP !== "false") fail("DOOTAH_ALLOW_LOCAL_HTTP forbidden");
  const local = !production && env.DOOTAH_ALLOW_LOCAL_HTTP === "true" && u.hostname === "127.0.0.1";
  if (u.protocol !== "https:" && !local) fail("DOOTAH_CLOUD_ORIGIN HTTPS required");
  const host = env.DOOTAH_CLOUD_HOST ?? (production ? "" : "127.0.0.1");
  if (!["127.0.0.1", "0.0.0.0", "::1", "::"].includes(host)) fail("DOOTAH_CLOUD_HOST");
  const port = Number(env.PORT ?? 3100);
  if (!Number.isInteger(port) || port < 1 || port > 65535) fail("PORT");
  let proxies;
  try { proxies = trustedProxies(env.DOOTAH_TRUSTED_PROXIES); } catch { fail("DOOTAH_TRUSTED_PROXIES"); }
  if (production) {
    let db;
    try { db = new URL(secretInput("DOOTAH_CLOUD_DATABASE_URL", env)); } catch { fail("DOOTAH_CLOUD_DATABASE_URL"); }
    if (!["postgres:", "postgresql:"].includes(db.protocol) || !db.hostname || !db.username || decodeURIComponent(db.password).length < 24 || db.pathname.length < 2) fail("DOOTAH_CLOUD_DATABASE_URL");
    let upstream;
    try { upstream = new URL(env.DOOTAH_XPREM_ORIGIN); } catch { fail("DOOTAH_XPREM_ORIGIN"); }
    if (!["http:", "https:"].includes(upstream.protocol) || upstream.origin !== env.DOOTAH_XPREM_ORIGIN || upstream.username || upstream.password || upstream.origin === origin) fail("DOOTAH_XPREM_ORIGIN");
    if (!env.DOOTAH_DELIVERY_BINDINGS) fail("DOOTAH_DELIVERY_BINDINGS");
    if (!env.DOOTAH_JAVA || !env.DOOTAH_VALIDATOR_CLASSPATH) fail("DOOTAH_VALIDATOR_CLASSPATH / DOOTAH_JAVA");
  }
  return { production, origin, host, port, local, trustedProxies: proxies };
}
const uuid = (v) => typeof v === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v);
export function loadBindings(env = process.env) {
  try {
    const production = env.DOOTAH_MODE === "production";
    const s = statSync(env.DOOTAH_DELIVERY_BINDINGS);
    if (!s.isFile() || s.size > 1048576 || (production && (s.mode & 0o077))) fail("bindings file");
    const config = JSON.parse(readFileSync(env.DOOTAH_DELIVERY_BINDINGS, "utf8"));
    if (!config || Array.isArray(config) || typeof config !== "object") fail("bindings object");
    const used = new Set();
    for (const [app, b] of Object.entries(config)) {
      if (!uuid(app) || !b || !uuid(b.appId) || used.has(b.appId)) fail("binding identity");
      used.add(b.appId);
      const base = new URL(b.base);
      if (!["http:", "https:"].includes(base.protocol) || base.origin !== b.base || base.username || base.password) fail("binding endpoint");
      if (production && (b.base !== env.DOOTAH_XPREM_ORIGIN || b.publicOrigin !== env.DOOTAH_CLOUD_ORIGIN)) fail("binding origin");
      if (typeof b.apiKey !== "string" || b.apiKey.length < 32 || b.apiKey.length > 512 || /\s/.test(b.apiKey)) fail("binding credential");
      const cert = new X509Certificate(b.certificate);
      if (cert.publicKey.asymmetricKeyType !== "rsa" || cert.publicKey.asymmetricKeyDetails.modulusLength < 2048 || !cert.verify(cert.publicKey) || Date.parse(cert.validFrom) > Date.now() || Date.parse(cert.validTo) <= Date.now()) fail("binding certificate");
    }
    return config;
  } catch { fail("DOOTAH_DELIVERY_BINDINGS invalid or unreadable"); }
}
export async function validatorReady(env = process.env) {
  try {
    const cp = env.DOOTAH_VALIDATOR_CLASSPATH;
    if (!cp) throw Error();
    for (const part of cp.split(delimiter)) {
      if (part.endsWith("/*")) {
        if (!readdirSync(part.slice(0, -2)).some(x => x.endsWith(".jar"))) throw Error();
      } else statSync(part);
    }
    const run = promisify(execFile);
    const version = await run(env.DOOTAH_JAVA ?? "java", ["-version"], { timeout: 5000 });
    if (!/version "21[.\"]/.test(version.stderr)) throw Error();
    await run(env.DOOTAH_JAVA ?? "java", ["-Xmx128m", "-cp", cp, "Validate", "--self-test"], { timeout: 5000 });
  } catch { fail("DOOTAH_JAVA / DOOTAH_VALIDATOR_CLASSPATH unavailable"); }
}
export async function runtimeDatabaseReady(pool) {
  try {
    const { rows: [r] } = await pool.query(`SELECT current_user, rolsuper, rolcreatedb, rolcreaterole, rolbypassrls,
      has_schema_privilege(current_user,'dootah_cloud','CREATE') AS schema_create,
      has_schema_privilege(current_user,'public','CREATE') AS public_create,
      has_database_privilege(current_user,current_database(),'CREATE') AS database_create,
      pg_has_role(current_user,(SELECT nspowner FROM pg_namespace WHERE nspname='dootah_cloud'),'MEMBER') AS owner_member,
      (SELECT max(version) FROM dootah_cloud.migrations) AS version
      FROM pg_roles WHERE rolname=current_user`);
    if (!r || r.version !== 6 || [r.rolsuper,r.rolcreatedb,r.rolcreaterole,r.rolbypassrls,r.schema_create,r.public_create,r.database_create,r.owner_member].some(Boolean)) throw Error();
    await pool.query("SELECT id FROM dootah_cloud.apps LIMIT 1");
  } catch { fail("runtime database unavailable, unmigrated, or overprivileged"); }
}
export async function productionReady(pool, env = process.env) {
  configuration(env);
  const bindings = loadBindings(env);
  await runtimeDatabaseReady(pool);
  for (const app of Object.keys(bindings)) {
    if (!(await pool.query("SELECT id FROM apps WHERE id=$1", [app])).rowCount) fail("binding Cloud app does not exist");
  }
  await validatorReady(env);
}
