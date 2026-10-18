import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import pg from 'pg';
import { pool, tx, password, secret, sha } from '../core.mjs';
import { policy } from '../policy.mjs';
import { issueInvitation, platformOperator, publishUsage } from '../hosted.mjs';
import { trustedProxies } from '../client.mjs';
import { runtimeDatabaseReady } from '../config.mjs';
import { usageSummary } from '../traffic.mjs';
const enabled=Boolean(process.env.DOOTAH_TEST_HOSTED_OWNER_FILE);
let operator,server;
after(async()=>{if(server)await new Promise(r=>server.close(r));await pool.end();await operator?.end();});
if (!enabled) test('hosted protection requires disposable fixture', {skip:true},()=>{});
if (enabled) {
  const url=new URL((await readFile(process.env.DOOTAH_TEST_HOSTED_OWNER_FILE,'utf8')).trim());
  assert.equal(url.hostname,'127.0.0.1','this test never accepts a remote database');
  operator=new pg.Pool({connectionString:url.href,options:'-c search_path=dootah_cloud,public'});
  assert(policy.enabled);
  const {createServer}=await import('../server.mjs');
  server=createServer(trustedProxies('127.0.0.1'));
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const base=`http://127.0.0.1:${server.address().port}`;
  const pass=secret();let ip=1;
  async function request(path,method='GET',body,auth={},extra={}) {
    const response=await fetch(base+'/v1/'+path,{method,headers:{Origin:'http://127.0.0.1:3100','Content-Type':'application/json','X-Forwarded-For':`198.18.${Math.floor(ip/250)}.${ip++%250+1}`,...auth,...extra},...(body===undefined?{}:{body:JSON.stringify(body)})});
    return {status:response.status,body:await response.json(),cookie:response.headers.get('set-cookie')?.split(';')[0],retry:response.headers.get('retry-after')};
  }
  async function issue(email) {
    const db=await operator.connect();
    try {await db.query('BEGIN');const r=await issueInvitation(db,email);await db.query('COMMIT');return r;}
    catch(e){await db.query('ROLLBACK');throw e;}finally{db.release();}
  }
  const login=async email=>{
    const r=await request('sessions','POST',{email,password:pass});assert.equal(r.status,200,JSON.stringify(r.body));
    return {Cookie:r.cookie,'X-CSRF-Token':r.body.csrf};
  };
  const create=async()=>{
    const email=randomUUID()+'@example.invalid',invite=await issue(email);
    const r=await request('users','POST',{email,password:pass,invitation:invite.token});assert.equal(r.status,201,JSON.stringify(r.body));
    return {id:r.body.id,email,auth:await login(email)};
  };
  let account,org,app,env;
  const capabilities=JSON.parse(await readFile(new URL('../../android-sdk/runtime/src/main/assets/dootah-capabilities.json',import.meta.url),'utf8'));
  const fid='dth1:'+'a'.repeat(64);
  const artifact={schema:'dootah.portable',runtimeAbi:3,logicAbi:1,runtimeVersion:'hosted-test',requires:['logic.pure.v1','compose.basicText.v1'],overrides:[{functionId:fid,parameters:[],expression:{op:'literal',type:'String',value:'Hosted test'}}]};
  test('runtime cannot administer invitations or pause controls; existing accounts and sessions survive',async()=>{
    await runtimeDatabaseReady(pool);
    await assert.rejects(platformOperator(pool),e=>e.status===403);
    await assert.rejects(pool.query('INSERT INTO invitations(id,email,digest) VALUES($1,$2,$3)',[randomUUID(),'x@x.test',sha(secret())]),e=>e.code==='42501');
    await assert.rejects(pool.query('UPDATE invitations SET revoked_at=now()'),e=>e.code==='42501');
    await assert.rejects(pool.query('UPDATE hosted_controls SET registrations_paused=true'),e=>e.code==='42501');
    const id=randomUUID();await operator.query('INSERT INTO users VALUES($1,$2,$3)',[id,'LegacyOwner@example.invalid',password(pass)]);
    const auth=await login('LegacyOwner@example.invalid');
    assert.equal((await request('sessions/current','GET',undefined,auth)).status,200);
    assert.equal((await request('invitations','POST',{email:'x@x.test'},auth)).status,403);
  });
  test('uninvited, invalid, expired, revoked and wrong-email registration share a bounded response',async()=>{
    const email=randomUUID()+'@example.invalid';const invite=await issue(email);
    for(const body of [{email,password:pass},{email,password:pass,invitation:secret()},{email:'other@example.invalid',password:pass,invitation:invite.token}]) {
      const r=await request('users','POST',body);assert.equal(r.status,403);assert.equal(r.body.error.code,'registration_unavailable');
    }
    await operator.query("UPDATE invitations SET expires_at=now()-interval '1 second' WHERE id=$1",[invite.id]);
    assert.equal((await request('users','POST',{email,password:pass,invitation:invite.token})).status,403);
    const revoked=await issue(email);await operator.query('UPDATE invitations SET revoked_at=now() WHERE id=$1',[revoked.id]);
    assert.equal((await request('users','POST',{email,password:pass,invitation:revoked.token})).status,403);
    assert.equal((await request('users','POST',{email,password:pass,invitation:revoked.token,role:'Owner'})).status,422);
  });
  test('concurrent redemption commits exactly one hashed account and prevents replay and email enumeration',async()=>{
    const email=randomUUID()+'@example.invalid';const invite=await issue(email);
    const body={email:email.toUpperCase(),password:pass,invitation:invite.token};
    const results=await Promise.all([request('users','POST',body),request('users','POST',body)]);
    assert.deepEqual(results.map(r=>r.status).sort(),[201,403]);
    const id=results.find(r=>r.status===201).body.id;
    const record=(await operator.query('SELECT * FROM invitations WHERE id=$1',[invite.id])).rows[0];
    assert.equal(record.digest,sha(invite.token));assert.equal(record.redeemed_by,id);assert(!JSON.stringify(record).includes(invite.token));
    assert.equal(Math.round((record.expires_at-record.created_at)/86400000),7);
    assert.notEqual((await pool.query('SELECT password_hash FROM users WHERE id=$1',[id])).rows[0].password_hash,pass);
    const another=await issue(email);
    for(const token of [invite.token,another.token]) assert.equal((await request('users','POST',{email,password:pass,invitation:token})).body.error.code,'registration_unavailable');
    const auth=await login(email.toUpperCase());account={id,email,auth};
    const r=await request('organizations','POST',{name:'Invited organization'},auth);assert.equal(r.status,200);org=r.body.id;auth['Dootah-Organization']=org;
    assert.equal((await request('invitations','POST',{email:'other@example.invalid'},auth)).status,404);
    assert.equal((await request('organizations','POST',{name:'Second'},auth)).status,409);
  });
  test('same-email invitations cannot race account creation; pending invitations are bounded',async()=>{
    const email=randomUUID()+'@example.invalid';const invitations=await Promise.all([issue(email),issue(email.toUpperCase())]);
    const result=await Promise.all(invitations.map(i=>request('users','POST',{email,password:pass,invitation:i.token})));
    assert.deepEqual(result.map(r=>r.status).sort(),[201,403]);
    await operator.query('UPDATE invitations SET revoked_at=now() WHERE redeemed_at IS NULL');
    for(let n=0;n<policy.pendingInvitations-1;n++)await issue(randomUUID()+'@example.invalid');
    const results=await Promise.allSettled([issue('one@example.invalid'),issue('two@example.invalid')]);
    assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
    assert.equal(results.find(r=>r.status==='rejected').reason.quota.resource,'pending_invitations');
    await operator.query('UPDATE invitations SET revoked_at=now() WHERE redeemed_at IS NULL');
  });
  test('application and environment creation serialize at quota boundaries; existing resources remain readable',async()=>{
    const results=await Promise.all([1,2].map(n=>request('apps','POST',{name:'App '+n},account.auth)));
    assert.deepEqual(results.map(r=>r.status).sort(),[200,429]);app=results.find(r=>r.status===200).body.id;
    assert.equal(results.find(r=>r.status===429).body.error.quota.remaining,0);
    const createEnv=name=>request('environments','POST',{appId:app,name,runtime:'hosted-test',contract:{capabilities,functions:{[fid]:[]}}},account.auth);
    const first=await createEnv('one');assert.equal(first.status,200);env=first.body.id;
    const e=await Promise.all(['two','three'].map(createEnv));assert.deepEqual(e.map(r=>r.status).sort(),[200,429]);
    const extra=randomUUID();await operator.query('INSERT INTO apps(id,organization_id,name,delivery_key) VALUES($1,$2,$3,$1::uuid::text)',[extra,org,'Grandfathered']);
    assert.equal((await request('apps/'+extra,'GET',undefined,account.auth)).status,200);
    assert.equal((await request('apps','POST',{name:'Over'},account.auth)).status,429);
    const foreign=await create();assert.equal((await request('apps/'+app,'GET',undefined,{...foreign.auth,'Dootah-Organization':org})).status,404);
  });
  test('artifact bytes fail with 413 before validator or persistence',async()=>{
    for(const artifact of [{large:'x'.repeat(65537)},{large:'x'.repeat(5*1024*1024)}]) {
      const r=await request('releases','POST',{environmentId:env,revision:'oversized',artifact},account.auth,{'Idempotency-Key':randomUUID()});assert.equal(r.status,413);
    }
    assert.equal((await pool.query('SELECT count(*)::int AS n FROM releases WHERE organization_id=$1',[org])).rows[0].n,0);
  });
  let publication,publicationBody,key;
  test('publish reservations serialize across environments, retries do not count twice, rollback is excluded',async()=>{
    publicationBody={environmentId:env,revision:'test',artifact};key=randomUUID();
    // Seed nine successful durable publications and a rollback, all in this disposable DB.
    for(let n=0;n<9;n++) {
      const id=randomUUID();
      await operator.query("INSERT INTO releases(id,organization_id,app_id,environment_id,runtime,runtime_abi,logic_abi,revision,hash,artifact,creator,status,percentage,branch,contract_digest) VALUES($1,$2,$3,$4,'hosted-test',3,1,'old','hash','{}',$5,'ready',0,$1::uuid::text,'contract')",[id,org,app,env,account.id]);
      await operator.query("INSERT INTO operations(id,organization_id,resource_id,actor,key,digest,action,status,completed_at) VALUES($1,$2,$3,$4,$1::uuid::text,'digest','publish','succeeded',now())",[randomUUID(),org,id,account.id]);
    }
    const env2=(await pool.query('SELECT id FROM environments WHERE app_id=$1 AND id<>$2',[app,env])).rows[0].id;
    const results=await Promise.all([request('releases','POST',publicationBody,account.auth,{'Idempotency-Key':key}),request('releases','POST',{...publicationBody,environmentId:env2},account.auth,{'Idempotency-Key':randomUUID()})]);
    assert.deepEqual(results.map(r=>r.status).sort(),[200,429],JSON.stringify(results));
    publication=results.find(r=>r.status===200).body;
    if(results[0].status!==200){publicationBody.environmentId=env2;key=(await operator.query('SELECT key FROM operations WHERE id=$1',[publication.id])).rows[0].key;}
    let usage=await publishUsage(pool,org);assert.equal(usage.used,9);assert.equal(usage.reserved,1);assert.equal(usage.remaining,0);
    assert.equal((await request('releases','POST',publicationBody,account.auth,{'Idempotency-Key':key})).body.id,publication.id);
    await operator.query("UPDATE operations SET status='succeeded',completed_at=now() WHERE id=$1",[publication.id]);
    await operator.query("UPDATE releases SET status='ready' WHERE id=$1",[publication.resource_id]);
    usage=await publishUsage(pool,org);assert.equal(usage.used,10);assert.equal(usage.reserved,0);
    await operator.query("INSERT INTO operations(id,organization_id,resource_id,actor,key,digest,action,status,completed_at) VALUES($1,$2,$3,$4,$1::uuid::text,'digest','rollback','succeeded',now())",[randomUUID(),org,publication.resource_id,account.id]);
    assert.equal((await publishUsage(pool,org)).used,10);
    await operator.query("UPDATE operations SET completed_at=now()-interval '31 days' WHERE organization_id=$1 AND action='publish' AND id<>$2",[org,publication.id]);
    assert.equal((await publishUsage(pool,org)).used,1);
  });
  test('emergency pauses preserve login, idempotent retries, metadata and rollback controls',async()=>{
    await operator.query('UPDATE hosted_controls SET registrations_paused=true,publications_paused=true');
    await assert.rejects(issue('paused@example.invalid'),e=>e.status===403);
    assert.equal((await request('users','POST',{email:'paused@example.invalid',password:pass})).body.error.code,'registrations_paused');
    await login(account.email);
    assert.equal((await request('releases','POST',publicationBody,account.auth,{'Idempotency-Key':key})).body.id,publication.id);
    assert.equal((await request('releases','POST',publicationBody,account.auth,{'Idempotency-Key':randomUUID()})).body.error.code,'publications_paused');
    assert.equal((await request('releases/'+publication.resource_id,'GET',undefined,account.auth)).status,200);
    await operator.query('UPDATE environments SET head=$2 WHERE id=$1',[publicationBody.environmentId,publication.resource_id]);
    const rollback=await request('releases/'+publication.resource_id+'/rollback','POST',{version:1},account.auth,{'Idempotency-Key':randomUUID()});assert.equal(rollback.status,200,JSON.stringify(rollback.body));
    await operator.query('UPDATE hosted_controls SET registrations_paused=false,publications_paused=false');
  });
  test('active enrollment quota uses existing random installation identity and permits renewal',async()=>{
    const ids=Array.from({length:10},()=>randomUUID());
    const ticket=async()=>{const r=await request('enrollment-tickets','POST',{environmentId:env},account.auth);assert.equal(r.status,200);return r.body.secret;};
    for(const id of ids.slice(0,9)) {
      const token=await ticket();const r=await request('enroll','POST',{installationId:id,runtime:'hosted-test',nativeVersion:'1',sdkVersion:'test',embeddedId:randomUUID()},{Authorization:'Bearer '+token});assert.equal(r.status,200,JSON.stringify(r.body));
    }
    const tickets=await Promise.all([ticket(),ticket()]);
    const r=await Promise.all(tickets.map((token,n)=>request('enroll','POST',{installationId:n?randomUUID():ids[9],runtime:'hosted-test',nativeVersion:'1',sdkVersion:'test',embeddedId:randomUUID()},{Authorization:'Bearer '+token})));
    assert.deepEqual(r.map(r=>r.status).sort(),[200,429]);
    const token=await ticket();const body={installationId:ids[0],runtime:'hosted-test',nativeVersion:'1',sdkVersion:'test',embeddedId:randomUUID()};
    assert.equal((await request('enroll','POST',body,{Authorization:'Bearer '+token})).status,200);
    assert.equal((await request('enroll','POST',body,{Authorization:'Bearer '+token})).status,401);
  });
  test('API token flows remain operational; authentication and publishing limits return Retry-After',async()=>{
    await operator.query('DELETE FROM rate_limits WHERE key=$1',[sha('create:'+account.id)]);
    const token=await request('tokens','POST',{scopes:['app:read','release:publish'],expiresDays:1},account.auth);assert.equal(token.status,200);
    const auth={Authorization:'Bearer '+token.body.secret};assert.equal((await request('apps','GET',undefined,auth)).status,200);
    await operator.query('INSERT INTO rate_limits VALUES($1,$2,$3) ON CONFLICT(key) DO UPDATE SET window_id=$2,count=$3',[sha('publish-org:'+org),Math.floor(Date.now()/60000),policy.rates.publish]);
    const r=await request('releases','POST',publicationBody,auth,{'Idempotency-Key':randomUUID()});assert.equal(r.status,429);assert.equal(r.retry,'60');
    for(let n=0;n<policy.rates.loginEmail;n++) assert.equal((await request('sessions','POST',{email:'brute@example.invalid',password:pass})).status,401);
    const login=await request('sessions','POST',{email:'brute@example.invalid',password:pass});assert.equal(login.status,429);assert.equal(login.retry,'60');
    assert.equal((await request('tokens/'+token.body.id,'DELETE',undefined,account.auth)).status,200);
    assert.equal((await request('apps','GET',undefined,auth)).status,401);
  });
  test('global organization cap serializes simultaneous final-slot requests',async()=>{
    const accounts=await Promise.all([create(),create()]);
    for(let n=0;n<3;n++)await operator.query('INSERT INTO organizations(id,name) VALUES($1,$2)',[randomUUID(),'Fixture']);
    const r=await Promise.all(accounts.map(a=>request('organizations','POST',{name:'Last slot'},a.auth)));
    assert.deepEqual(r.map(r=>r.status).sort(),[200,429]);
    assert.equal((await pool.query('SELECT count(*)::int AS n FROM organizations WHERE hosted_external')).rows[0].n,5);
  });
  test('operator usage summary includes reservations, unknown historical sizes and warning thresholds',async()=>{
    await operator.query("INSERT INTO hosted_usage(month,organization_id,requests,estimated_bytes) VALUES(date_trunc('month',now() AT TIME ZONE 'UTC')::date,'00000000-0000-0000-0000-000000000000',800,86000)");
    const r=await usageSummary(operator);assert.equal(r.organizations.find(r=>r.id===org).apps,'2');
    assert(r.organizations.find(r=>r.id===org).unknown_artifact_sizes>0);assert.equal(r.warnings[0].level,'warning');assert.equal(r.warnings[1].level,'critical');
    const usage=await request('usage','GET',undefined,account.auth);assert.equal(usage.status,200);assert.equal(usage.body.hosted,true);
  });
}
