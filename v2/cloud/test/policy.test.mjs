import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { hostedPolicy } from '../policy.mjs';
import { admission, usageMeter } from '../traffic.mjs';
import { pool } from '../core.mjs';
after(() => pool.end());
test('hosted policy is opt-in, validates all settings and requires explicit production budgets', () => {
  assert.equal(hostedPolicy({}).enabled,false);
  const p=hostedPolicy({DOOTAH_HOSTED_ALPHA:'true'});
  assert.deepEqual([p.organizations,p.apps,p.environments,p.publishes,p.windowDays,p.installations,p.artifactBytes],[5,1,2,10,30,10,5242880]);
  for(const env of [{DOOTAH_HOSTED_ALPHA:'yes'}, {DOOTAH_HOSTED_APPS:'0'}, {DOOTAH_RATE_IP:'NaN'}, {DOOTAH_HOSTED_WARNING_PERCENT:'86'}, {DOOTAH_HOSTED_ALPHA:'true',DOOTAH_MODE:'production'}]) assert.throws(()=>hostedPolicy(env),/configuration/);
});
test('pre-database admission bounds parallel work, IP attempts and rotating clients; releases capacity', () => {
  const p=hostedPolicy({DOOTAH_HOSTED_ALPHA:'true',DOOTAH_RATE_CONCURRENT:'2',DOOTAH_RATE_GLOBAL:'6',DOOTAH_RATE_LOGIN:'2'});
  let now=0; const admit=admission(p,()=>now);
  const a=admit('one','/v1/sessions'),b=admit('two','/v1/sessions');
  assert.throws(()=>admit('three','/v1/sessions'),e=>e.status===429);
  a();a();b();
  admit('one','/v1/sessions')();
  assert.throws(()=>admit('one','/v1/sessions'),e=>e.status===429);
  for(const client of ['four','five','six']) admit(client,'/manifest')();
  assert.throws(()=>admit('seven','/manifest'),e=>e.status===429);
  now=60000;admit('one','/v1/sessions')();
});
test('usage batches checks and byte estimates per month without per-request DB writes', async () => {
  const meter=usageMeter(); let writes=0;
  const db={query: async(sql,values)=>{writes++;assert.equal(values[2][0],3);assert.equal(values[3][0],2);assert.equal(values[4][0],60);}};
  meter.record('00000000-0000-4000-8000-000000000001','/manifest',10);
  meter.record('00000000-0000-4000-8000-000000000001','/manifest',20);
  meter.record(null,'/v1/sessions',30);
  assert.equal(writes,0);await meter.flush(db);await meter.flush(db);assert.equal(writes,1);
});
