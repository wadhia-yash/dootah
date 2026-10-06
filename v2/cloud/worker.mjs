import { pool, tx, audit, requireValue } from "./core.mjs";
import * as adapter from "./adapter.mjs";
export async function work() {
  const { rows: pending } = await pool.query(
    "SELECT * FROM operations WHERE status IN ('pending','running') AND next_attempt_at<=now() ORDER BY next_attempt_at,created_at LIMIT 10",
  );
  for (const op of pending) {
    const db = await pool.connect();
    let locked = false;
    try {
      const {
        rows: [l],
      } = await db.query(
        "SELECT pg_try_advisory_lock(hashtext($1)) AS locked",
        [op.id],
      );
      locked = l.locked;
      if (!locked) continue;
      const {
        rows: [current],
      } = await db.query(
        "SELECT * FROM operations WHERE id=$1 AND organization_id=$2",
        [op.id, op.organization_id],
      );
      if (!["pending", "running"].includes(current.status)) continue;
      await db.query(
        "UPDATE operations SET status='running',attempts=attempts+1,next_attempt_at=now()+interval '30 seconds' WHERE id=$1 AND organization_id=$2",
        [op.id, op.organization_id],
      );
      const {
        rows: [r],
      } = await db.query(
        "SELECT * FROM releases WHERE id=$1 AND organization_id=$2",
        [op.resource_id, op.organization_id],
      );
      const branch = op.action === "publish" ? r.branch : `cloud-${op.id}`;
      await adapter.prepare(r.app_id, branch);
      let result = await adapter.inspect(r.app_id, branch, r.runtime);
      if (!result) {
        if (op.action === "publish") await adapter.upload(r);
        else await adapter.rollback(r, branch);
        result = await adapter.inspect(r.app_id, branch, r.runtime);
      }
      requireValue(result, "missing_delivery_receipt", 502);
      if (op.action === "publish")
        requireValue(
          result.launchAsset?.hash ===
            Buffer.from(r.hash, "hex").toString("base64url"),
          "artifact_hash_mismatch",
          502,
        );
      else
        requireValue(
          result.type === "rollBackToEmbedded",
          "signed_rollback_required",
          502,
        );
      await tx(async (conn) => {
        const auth = { organization_id: op.organization_id, user_id: op.actor };
        if (op.action === "publish") {
          await conn.query(
            "UPDATE releases SET status='ready',upstream_uuid=$3,artifact='{}',artifact_bytes=NULL WHERE organization_id=$1 AND id=$2",
            [op.organization_id, r.id, result.id],
          );
        } else {
          await conn.query(
            "UPDATE releases SET status='paused',version=version+1 WHERE organization_id=$1 AND environment_id=$2 AND id<>$3 AND status IN ('active','rolling_out')",
            [op.organization_id, r.environment_id, r.id],
          );
          await conn.query(
            "UPDATE releases SET status=$3,version=version+1 WHERE organization_id=$1 AND id=$2",
            [
              op.organization_id,
              r.id,
              op.action === "kill" ? "killed" : "rolled_back",
            ],
          );
          await conn.query(
            "UPDATE environments SET directive_branch=$3 WHERE organization_id=$1 AND id=$2",
            [op.organization_id, r.environment_id, branch],
          );
        }
        await conn.query(
          "UPDATE operations SET status='succeeded',receipt=$3 WHERE organization_id=$1 AND id=$2",
          [
            op.organization_id,
            op.id,
            {
              signatureVerified: true,
              ...(op.action === "publish"
                ? { updateId: result.id }
                : { type: result.type, parameters: result.parameters }),
            },
          ],
        );
        await audit(conn, auth, `${op.action}.completed`, r.id, {
          operationId: op.id,
        });
      });
    } catch (e) {
      // Ambiguous upstream failures retain intent for reconciliation; never blindly duplicate an operation.
      await db.query(
        "UPDATE operations SET status='pending',next_attempt_at=now()+least(300,power(2,least(attempts,8))) * interval '1 second' WHERE organization_id=$1 AND id=$2",
        [op.organization_id, op.id],
      );
      console.error(
        "Cloud operation awaiting reconciliation",
        op.id,
        e.status ?? "internal",
      );
    } finally {
      try {
        if (locked)
          await db.query("SELECT pg_advisory_unlock(hashtext($1))", [op.id]);
      } finally {
        db.release();
      }
    }
  }
}
