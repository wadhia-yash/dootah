import { Fault, sha } from './core.mjs';
import { policy } from './policy.mjs';
// Bounded, per-process admission before body parsing, password hashing or a DB lease.
// Durable account/organization limits still use the shared PostgreSQL limiter.
export function admission(p = policy, clock = Date.now) {
  let minute = -1, total = 0, authentication = 0, active = 0;
  const buckets = new Map();
  return (client, path) => {
    if (!p.enabled) return () => {};
    const current = Math.floor(clock()/60000);
    if (minute !== current) { minute = current; total = 0; authentication = 0; buckets.clear(); }
    const category = path === '/v1/users' ? 'registration' : path === '/v1/sessions' ? 'login' :
      ['/manifest','/assets'].includes(path) ? 'delivery' : 'ip';
    const key = sha(category + ':' + client);
    const n = (buckets.get(key) ?? 0) + 1;
    // No eviction on saturation: rotating addresses cannot reset existing buckets.
    const authenticating = ['registration','login'].includes(category);
    if ((authenticating && authentication >= p.rates.authentication) || total >= p.rates.global || (!buckets.has(key) && buckets.size >= 4096) ||
        n > p.rates[category] || active >= p.rates.concurrent) throw new Fault(429, 'rate_limited');
    buckets.set(key, n); total++; active++; if (authenticating) authentication++;
    let done = false;
    return () => { if (!done) { active--; done = true; } };
  };
}
export function memoryLimit() {
  let minute = -1;
  const buckets = new Map();
  return (key, maximum) => {
    const current = Math.floor(Date.now()/60000);
    if (current !== minute) { minute = current; buckets.clear(); }
    const digest = sha(key), count = (buckets.get(digest) ?? 0) + 1;
    if ((!buckets.has(digest) && buckets.size >= 4096) || count > maximum) throw new Fault(429,'rate_limited');
    buckets.set(digest,count);
  };
}
const aggregate = '00000000-0000-0000-0000-000000000000';
// Approximate body-byte counters; one batched write/minute, no per-check writes.
export function usageMeter() {
  let rows = new Map(), flushing = false;
  function add(month, organization, requests, checks, bytes) {
    const key = month + ':' + organization;
    if (!rows.has(key) && rows.size >= 1024) return;
    const r = rows.get(key) ?? { month, organization, requests: 0, checks: 0, bytes: 0 };
    r.requests += requests; r.checks += checks; r.bytes += bytes; rows.set(key,r);
  }
  return {
    record(organization, path, bytes, now = new Date()) {
      const month = now.toISOString().slice(0,7) + '-01';
      add(month,aggregate,1,path === '/manifest' ? 1 : 0,bytes);
      if (organization) add(month,organization,1,path === '/manifest' ? 1 : 0,bytes);
    },
    async flush(db) {
      if (flushing || !rows.size) return;
      flushing = true;
      const batch = rows; rows = new Map();
      try {
        const values = [...batch.values()];
        await db.query(`INSERT INTO hosted_usage(month,organization_id,requests,update_checks,estimated_bytes)
          SELECT * FROM unnest($1::date[],$2::uuid[],$3::bigint[],$4::bigint[],$5::bigint[])
          ON CONFLICT(month,organization_id) DO UPDATE SET requests=hosted_usage.requests+excluded.requests,
          update_checks=hosted_usage.update_checks+excluded.update_checks,estimated_bytes=hosted_usage.estimated_bytes+excluded.estimated_bytes`,
          [values.map(r=>r.month),values.map(r=>r.organization),values.map(r=>r.requests),values.map(r=>r.checks),values.map(r=>r.bytes)]);
      } catch {
        // Retrying an ambiguous DB commit could double count. Estimates may undercount.
        console.error(JSON.stringify({event:'usage_flush_unconfirmed'}));
      } finally { flushing = false; }
    },
  };
}
export async function usageSummary(db) {
  const { rows: usage } = await db.query("SELECT * FROM hosted_usage WHERE month=date_trunc('month',now() AT TIME ZONE 'UTC')::date ORDER BY organization_id");
  const total = usage.find(r => r.organization_id === aggregate);
  const consumed = { requests: Number(total?.requests ?? 0), estimatedBytes: Number(total?.estimated_bytes ?? 0) };
  const elapsed = new Date().getUTCDate();
  const days = new Date(Date.UTC(new Date().getUTCFullYear(),new Date().getUTCMonth()+1,0)).getUTCDate();
  const warnings = [];
  for (const [name,value,budget] of [['requests',consumed.requests,policy.requestBudget],['estimatedBytes',consumed.estimatedBytes,policy.transferBudget]]) {
    const percent = budget ? value / budget * 100 : null;
    warnings.push({ resource:name, budget, percent, level: percent === null ? 'unconfigured' : percent >= policy.criticalPercent ? 'critical' : percent >= policy.warningPercent ? 'warning' : 'normal', projectedMonth: Math.ceil(value/elapsed*days) });
  }
  const { rows: organizations } = await db.query(`SELECT o.id,o.name,o.hosted_external,
    (SELECT count(*) FROM apps a WHERE a.organization_id=o.id) AS apps,
    (SELECT count(*) FROM operations p WHERE p.organization_id=o.id AND p.action='publish' AND p.status='succeeded' AND p.completed_at>now()-$1*interval '1 day') AS publishes,
    (SELECT count(*) FROM operations p WHERE p.organization_id=o.id AND p.action='publish' AND p.status IN ('pending','running')) AS reserved,
    (SELECT coalesce(sum(size_bytes),0) FROM releases r WHERE r.organization_id=o.id) AS known_artifact_bytes,
    (SELECT count(*) FROM releases r WHERE r.organization_id=o.id AND size_bytes IS NULL) AS unknown_artifact_sizes
    FROM organizations o ORDER BY o.id`, [policy.windowDays]);
  return { policy, controls: (await db.query('SELECT registrations_paused,publications_paused FROM hosted_controls')).rows[0],
    consumed,warnings,organizations,usage,measurement:'Estimated Cloud body bytes/requests since enablement, excluding edge rejection, protocol overhead and other provider traffic. Not provider billing. Flushes may be lost on crash.' };
}
