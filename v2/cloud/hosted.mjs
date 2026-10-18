import { randomUUID } from 'node:crypto';
import { Fault, requireValue, sha, secret, str } from './core.mjs';
import { policy } from './policy.mjs';
export const normalizedEmail = value => str(value).trim().toLowerCase();
export function quota(resource, maximum, used, resetAt = null) {
  const remaining = Math.max(0, maximum - used);
  const detail = { resource, limit: maximum, used, remaining, resetAt };
  if (used >= maximum) {
    const error = new Fault(429, 'quota_exceeded');
    error.quota = detail;
    error.retryAfter = resetAt ? Math.max(1, Math.ceil((Date.parse(resetAt) - Date.now()) / 1000)) : undefined;
    throw error;
  }
  return detail;
}
export async function controls(db, kind) {
  const { rows: [c] } = await db.query('SELECT * FROM hosted_controls WHERE id=true FOR SHARE');
  requireValue(c && !c[kind + '_paused'], kind + '_paused', 403);
}
export async function lockOrganization(db, id) {
  await db.query('SELECT id FROM organizations WHERE id=$1 FOR UPDATE', [id]);
}
export async function platformOperator(db) {
  const { rows: [r] } = await db.query("SELECT pg_has_role(current_user,nspowner,'MEMBER') AS allowed FROM pg_namespace WHERE nspname='dootah_cloud'");
  requireValue(r?.allowed, 'platform_operator_required', 403);
}
// Only the existing operator CLI, using the migration-owner connection, exposes these.
export async function issueInvitation(db, email) {
  await platformOperator(db);
  requireValue(policy.enabled, 'hosted_policy_disabled', 403);
  await controls(db, 'registrations');
  await db.query('SELECT pg_advisory_xact_lock(70427002)');
  email = normalizedEmail(email);
  requireValue(/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email), 'invalid_email');
  const { rows: [count] } = await db.query("SELECT count(*)::int AS n FROM invitations WHERE revoked_at IS NULL AND redeemed_at IS NULL AND expires_at>now()");
  quota('pending_invitations', policy.pendingInvitations, count.n);
  const raw = secret(), id = randomUUID();
  await db.query('INSERT INTO invitations(id,email,digest) VALUES($1,$2,$3)', [id,email,sha(raw)]);
  return { id, token: raw, expiresInDays: 7 };
}
export async function redeemInvitation(db, email, token, userId) {
  // Missing, unknown, wrong email, expired, revoked and consumed all have one result.
  requireValue(typeof token === 'string' && /^[A-Za-z0-9_-]{43}$/.test(token), 'registration_unavailable', 403);
  const { rows: [invitation] } = await db.query(
    'SELECT *, expires_at>clock_timestamp() AS unexpired FROM invitations WHERE digest=$1 FOR UPDATE', [sha(token)]);
  requireValue(invitation && invitation.email === normalizedEmail(email) && !invitation.revoked_at &&
    !invitation.redeemed_at && invitation.unexpired, 'registration_unavailable', 403);
  // Same-email invitations cannot race account creation (including case variants).
  await db.query('SELECT pg_advisory_xact_lock(hashtextextended($1,70427003))', [normalizedEmail(email)]);
  const existing = await db.query('SELECT id FROM users WHERE lower(email)=$1', [normalizedEmail(email)]);
  requireValue(!existing.rowCount, 'registration_unavailable', 403);
  // Caller inserts the user first; the FK and redemption are committed together.
  return async () => {
    const result = await db.query('UPDATE invitations SET redeemed_at=now(),redeemed_by=$2 WHERE id=$1 AND expires_at>clock_timestamp() AND revoked_at IS NULL AND redeemed_at IS NULL', [invitation.id,userId]);
    requireValue(result.rowCount === 1, 'registration_unavailable', 403);
  };
}
export async function resourceQuota(db, organization, resource, app) {
  if (!policy.enabled) return;
  await lockOrganization(db, organization);
  let result;
  if (resource === 'apps') result = await db.query('SELECT count(*)::int AS n FROM apps WHERE organization_id=$1', [organization]);
  else if (resource === 'environments') result = await db.query('SELECT count(*)::int AS n FROM environments WHERE organization_id=$1 AND app_id=$2', [organization,app]);
  else throw Error('unknown quota');
  quota(resource, policy[resource], result.rows[0].n);
}
export async function publishUsage(db, organization) {
  const { rows: [r] } = await db.query(`SELECT
    count(*) FILTER (WHERE status='succeeded' AND completed_at>now()-$2*interval '1 day')::int AS used,
    count(*) FILTER (WHERE status IN ('pending','running'))::int AS reserved,
    min(completed_at+$2*interval '1 day') FILTER (WHERE status='succeeded' AND completed_at>now()-$2*interval '1 day') AS reset
    FROM operations WHERE organization_id=$1 AND action='publish'`, [organization,policy.windowDays]);
  return { limit: policy.publishes, used: r.used, reserved: r.reserved, remaining: Math.max(0,policy.publishes-r.used-r.reserved), resetAt: r.reset?.toISOString() ?? null };
}
export async function publishQuota(db, organization) {
  if (!policy.enabled) return;
  await controls(db, 'publications');
  await lockOrganization(db, organization);
  const r = await publishUsage(db, organization);
  quota('publishes', policy.publishes, r.used+r.reserved, r.resetAt);
}
export async function organizationQuota(db, user) {
  if (!policy.enabled) return;
  await controls(db, 'registrations');
  await db.query('SELECT pg_advisory_xact_lock(70427002)');
  const { rows: [r] } = await db.query('SELECT count(*)::int AS n FROM organizations WHERE hosted_external');
  quota('organizations', policy.organizations, r.n);
  // A single invited developer cannot consume all five external organization slots.
  const owned = await db.query("SELECT 1 FROM members WHERE user_id=$1 AND role='Owner'", [user]);
  requireValue(!owned.rowCount, 'organization_already_owned', 409);
}
export async function installationQuota(db, organization, id) {
  if (!policy.enabled) return;
  await lockOrganization(db, organization);
  const { rows } = await db.query("SELECT DISTINCT installation_id FROM credentials WHERE organization_id=$1 AND kind='installation' AND revoked_at IS NULL AND expires_at>now()", [organization]);
  if (!rows.some(r => r.installation_id === id)) quota('installations', policy.installations, rows.length);
}
