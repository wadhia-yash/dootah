import { readFile } from "node:fs/promises";
import { secretInput } from "./config.mjs";
import { randomUUID } from "node:crypto";
import { pool, password, str } from "./core.mjs";
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
      for (const version of [3, 4, 5, 6])
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
  } else if (mode === "user") {
    const p = str(secretInput("DOOTAH_INITIAL_PASSWORD"), 256);
    if (p.length < 14)
      throw Error("Password must contain at least 14 characters");
    await pool.query("INSERT INTO users VALUES($1,$2,$3)", [
      randomUUID(),
      str(arg),
      password(p),
    ]);
  } else throw Error("Usage: admin.mjs migrate|user email");
  console.log("Completed");
} catch {
  console.error(JSON.stringify({event:"operator_command_failed", command: ["migrate", "user"].includes(mode) ? mode : "invalid"}));
  process.exitCode = 1;
} finally {
  await pool.end();
}
