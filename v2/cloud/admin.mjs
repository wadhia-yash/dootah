import { readFile } from "node:fs/promises";
import { secretInput } from "./config.mjs";
import { randomUUID } from "node:crypto";
import { pool, password, str, tx, uuid, requireValue } from "./core.mjs";
import { issueInvitation, platformOperator } from './hosted.mjs';
import { usageSummary } from './traffic.mjs';
const [mode, arg] = process.argv.slice(2);
try {
  if (mode === "migrate") {
    const db = await pool.connect();
    try {
      await db.query("BEGIN");
      await db.query("SELECT pg_advisory_xact_lock(70427001)");
      const {
        rows: [r],
      } = await db.query(
        "SELECT to_regclass('dootah_cloud.migrations') AS existing",
      );
      if (!r.existing)
        await db.query(
          await readFile(
            new URL("./migrations/001.sql", import.meta.url),
            "utf8",
          ),
        );
      const applied = await db.query(
        "SELECT version FROM dootah_cloud.migrations",
      );
      if (!applied.rows.some((r) => r.version === 2))
        await db.query(
          await readFile(
            new URL("./migrations/002.sql", import.meta.url),
            "utf8",
          ),
        );
      for (const version of [3, 4, 5, 6, 7, 8, 9])
        if (!applied.rows.some((r) => r.version === version))
          await db.query(
            await readFile(
              new URL(`./migrations/00${version}.sql`, import.meta.url),
              "utf8",
            ),
          );
      await db.query("COMMIT");
    } catch (error) {
      await db.query("ROLLBACK");
      throw error;
    } finally {
      db.release();
    }
  } else if (mode === 'invite') {
    const origin = new URL(process.env.DOOTAH_CLOUD_ORIGIN);
    requireValue(origin.protocol === 'https:' || process.env.DOOTAH_ALLOW_LOCAL_HTTP === 'true');
    const invitation = await tx(db => issueInvitation(db, arg));
    // Fragment never reaches HTTP access logs. This is the only display of the token.
    console.log(JSON.stringify({ id: invitation.id, expiresInDays: 7,
      link: `${origin.origin}/console#invitation=${invitation.token}` }));
  } else if (['invitations','revoke-invitation','pause-registrations','resume-registrations','pause-publications','resume-publications','usage'].includes(mode)) {
    const result = await tx(async db => {
      await platformOperator(db);
      if (mode === 'invitations') return (await db.query('SELECT id,email,created_at,expires_at,revoked_at,redeemed_at FROM invitations ORDER BY created_at DESC LIMIT 100')).rows;
      if (mode === 'revoke-invitation') {
        requireValue(uuid(arg));
        return { revoked: (await db.query('UPDATE invitations SET revoked_at=now() WHERE id=$1 AND redeemed_at IS NULL AND revoked_at IS NULL', [arg])).rowCount };
      }
      if (mode === 'usage') return usageSummary(db);
      const column = mode.endsWith('registrations') ? 'registrations_paused' : 'publications_paused';
      await db.query(`UPDATE hosted_controls SET ${column}=$1 WHERE id=true`, [mode.startsWith('pause-')]);
      return { [column]: mode.startsWith('pause-') };
    });
    console.log(JSON.stringify(result, null, 2));
  } else if (mode === "user") {
    await platformOperator(pool);
    const p = str(secretInput("DOOTAH_INITIAL_PASSWORD"), 256);
    if (p.length < 14)
      throw Error("Password must contain at least 14 characters");
    await pool.query("INSERT INTO users VALUES($1,$2,$3)", [
      randomUUID(),
      str(arg),
      password(p),
    ]);
  } else throw Error("Usage: admin.mjs migrate|user|invite|invitations|revoke-invitation|usage|pause-registrations|resume-registrations|pause-publications|resume-publications");
  console.log("Completed");
} catch {
  console.error(JSON.stringify({event:"operator_command_failed", command: ["migrate", "user"].includes(mode) ? mode : "invalid"}));
  process.exitCode = 1;
} finally {
  await pool.end();
}
