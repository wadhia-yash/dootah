// Run only by disposable deployment acceptance, after its ordinary delivery checks.
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID, verify, X509Certificate } from 'node:crypto';
import { pool } from '../core.mjs';
import { policy } from '../policy.mjs';
import { work } from '../worker.mjs';
const enabled=policy.enabled && process.env.DOOTAH_TEST_TENANTS && process.env.DOOTAH_TEST_ARTIFACT;
let server;
after(async()=>{if(server)await new Promise(r=>server.close(r));await pool.end();});
test('hosted exhaustion and pause preserve signed rollback; paused worker retains publication reservations', {skip:!enabled},async()=>{
  const tenants=JSON.parse(await readFile(process.env.DOOTAH_TEST_TENANTS));
  const artifact=JSON.parse(await readFile(process.env.DOOTAH_TEST_ARTIFACT));
  const paused=process.env.DOOTAH_TEST_PUBLICATIONS_PAUSED==='true';
  const a=tenants.a;
  const {createServer}=await import('../server.mjs');
  server=createServer();await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const base=`http://127.0.0.1:${server.address().port}`;
  async function request(path,body) {
    const r=await fetch(base+'/v1/'+path,{method:'POST',headers:{Authorization:'Bearer '+a.token,'Content-Type':'application/json','Idempotency-Key':randomUUID()},body:JSON.stringify(body)});
    return {status:r.status,body:await r.json()};
  }
  const publish=await request('releases',{environmentId:a.environment,revision:'Refused hosted publish',artifact});
  assert.equal(publish.status,paused?403:429);
  assert.equal(publish.body.error.code,paused?'publications_paused':'quota_exceeded');
  const headers={'expo-app-id':a.app,'expo-channel-name':'development','expo-runtime-version':artifact.runtimeVersion,'expo-platform':'android','expo-protocol-version':'1','eas-client-id':randomUUID(),'expo-embedded-update-id':randomUUID(),'expo-expect-signature':'sig, keyid="main", alg="rsa-v1_5-sha256"'};
  const r=await fetch(base+'/manifest',{headers});assert.equal(r.status,200);
  const text=await r.text();assert(text.includes('rollBackToEmbedded'));
  const boundary=/boundary="?([^";]+)/i.exec(r.headers.get('content-type'))[1];
  const part=text.split('--'+boundary).find(s=>/name="directive"/.test(s));
  const at=part.indexOf('\r\n\r\n'),body=part.slice(at+4,-2),signature=/expo-signature:.*?sig="([^"]+)"/i.exec(part.slice(0,at))[1];
  const bindings=JSON.parse(await readFile(process.env.DOOTAH_DELIVERY_BINDINGS));
  assert(verify('RSA-SHA256',Buffer.from(body),new X509Certificate(bindings[a.app].certificate).publicKey,Buffer.from(signature,'base64')));
  if(paused) {
    // Main worker is stopped by the harness. The test worker must not reconcile this
    // already-uploaded release while paused, even though inspect would succeed.
    const {rows:[old]}=await pool.query("SELECT * FROM operations WHERE organization_id=$1 AND action='publish' AND status='succeeded' LIMIT 1",[a.org]);
    assert(old);
    const ids=Array.from({length:11},()=>randomUUID());
    for (const id of ids) await pool.query("INSERT INTO operations(id,organization_id,resource_id,actor,key,digest,action,status,created_at) VALUES($1,$2,$3,$4,$5,'test','publish','pending',now()-interval '1 day')",[id,a.org,old.resource_id,old.actor,randomUUID()]);
    const rollback=randomUUID();
    await pool.query("INSERT INTO operations(id,organization_id,resource_id,actor,key,digest,action,status) VALUES($1,$2,$3,$4,$5,'test','rollback','pending')",[rollback,a.org,old.resource_id,old.actor,randomUUID()]);
    await work();
    assert.equal((await pool.query("SELECT count(*)::int AS n FROM operations WHERE id=ANY($1::uuid[]) AND status='pending'",[ids])).rows[0].n,11);
    assert.equal((await pool.query('SELECT status FROM operations WHERE id=$1',[rollback])).rows[0].status,'succeeded','paused publication backlog must never starve rollback');
    await pool.query('DELETE FROM operations WHERE id=ANY($1::uuid[])',[ids]);
  }
});
