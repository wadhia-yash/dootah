import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, writeFileSync, chmodSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {randomBytes} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {configuration, secretInput, loadBindings, validatorReady, runtimeDatabaseReady} from '../cloud/config.mjs';
const key=randomBytes(32).toString('hex');
const valid=()=>({DOOTAH_MODE:'production',NODE_ENV:'production',DOOTAH_CLOUD_ORIGIN:'https://updates.example.test',DOOTAH_CLOUD_HOST:'0.0.0.0',DOOTAH_CLOUD_DATABASE_URL:`postgresql://runtime:${key}@postgres/cloud`,DOOTAH_XPREM_ORIGIN:'http://xprem:3100',DOOTAH_DELIVERY_BINDINGS:'/private/bindings.json',DOOTAH_JAVA:'/java',DOOTAH_VALIDATOR_CLASSPATH:'/validator'});
test('production accepts explicit HTTPS configuration and container host',()=>{assert.equal(configuration(valid()).host,'0.0.0.0');});
test('production rejects HTTP even with local bypass',()=>{
 for (const extra of [{DOOTAH_CLOUD_ORIGIN:'http://127.0.0.1'},{DOOTAH_ALLOW_LOCAL_HTTP:'true'}]) assert.throws(()=>configuration({...valid(),...extra}));
});
test('production requires DB config and never includes rejected secrets in errors',()=>{
 for(const value of [undefined,`invalid-${key}`,`https://runtime:${key}@postgres/cloud`]) {
   assert.throws(()=>configuration({...valid(),DOOTAH_CLOUD_DATABASE_URL:value}),e=>e.message.includes('DOOTAH_CLOUD_DATABASE_URL')&&!e.stack.includes(key));
 }
});
test('production mode and host are explicit; origin excludes credentials paths and queries',()=>{
 for(const extra of [{DOOTAH_MODE:undefined},{DOOTAH_MODE:'prod'},{DOOTAH_CLOUD_HOST:undefined},{DOOTAH_CLOUD_ORIGIN:`https://${key}@example.test`},{DOOTAH_CLOUD_ORIGIN:'https://example.test/path'},{DOOTAH_CLOUD_ORIGIN:'https://example.test?query'}]) assert.throws(()=>configuration({...valid(),...extra}));
});
test('development preserves explicit loopback HTTP',()=>{assert.equal(configuration({DOOTAH_ALLOW_LOCAL_HTTP:'true'}).host,'127.0.0.1');});
test('file secrets require private permissions and unambiguous inputs',()=>{
 const d=mkdtempSync(join(tmpdir(),'dootah-secret-'));const p=join(d,'secret');
 try{writeFileSync(p,key,{mode:0o600});assert.equal(secretInput('KEY',{KEY_FILE:p}),key);assert.throws(()=>secretInput('KEY',{KEY:key,KEY_FILE:p}));chmodSync(p,0o644);assert.throws(()=>secretInput('KEY',{KEY_FILE:p}),e=>!e.stack.includes(key));}finally{rmSync(d,{recursive:true});}
});
test('malformed bindings are redacted and fail closed; empty bindings bootstrap allowed',()=>{
 const d=mkdtempSync(join(tmpdir(),'dootah-binding-'));const p=join(d,'bindings');
 try{for(const v of [key,'[]','null','{"bad":{"apiKey":"'+key+'"}}']){writeFileSync(p,v,{mode:0o600});assert.throws(()=>loadBindings({...valid(),DOOTAH_DELIVERY_BINDINGS:p}),e=>!e.stack.includes(key));}writeFileSync(p,'{}');assert.deepEqual(loadBindings({...valid(),DOOTAH_DELIVERY_BINDINGS:p}),{});}finally{rmSync(d,{recursive:true});}
});
test('missing Java or validator cannot report readiness',async()=>{await assert.rejects(validatorReady({DOOTAH_JAVA:'/missing',DOOTAH_VALIDATOR_CLASSPATH:'/missing'}),/DOOTAH_JAVA/);});
test('runtime readiness rejects migration owner and unavailable DB without leaking errors',async()=>{
 await assert.rejects(runtimeDatabaseReady({query:async()=>{throw Error(key);}}),e=>!e.stack.includes(key));
 for(const flag of ['rolsuper','rolcreatedb','rolcreaterole','rolbypassrls','schema_create','public_create','database_create','owner_member']) {
  await assert.rejects(runtimeDatabaseReady({query:async()=>({rows:[{version:6,[flag]:true}]})}),/overprivileged/);
 }
});
test('bindings enforce private endpoint, public origin, app exclusivity and certificate',()=>{
 const d=mkdtempSync(join(tmpdir(),'dootah-certificate-')),p=join(d,'bindings');
 try{
  const cert=spawnSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=local-test','-keyout',join(d,'key'),'-out',join(d,'cert')],{encoding:'utf8'});
  assert.equal(cert.status,0);
  const read=spawnSync('cat',[join(d,'cert')],{encoding:'utf8'}).stdout;
  const app='00000000-0000-4000-8000-000000000001',xp='00000000-0000-4000-8000-000000000002';
  const b={appId:xp,base:'http://xprem:3100',publicOrigin:'https://updates.example.test',apiKey:key,certificate:read};
  const env={...valid(),DOOTAH_DELIVERY_BINDINGS:p};
  writeFileSync(p,JSON.stringify({[app]:b}),{mode:0o600});assert.equal(loadBindings(env)[app].appId,xp);
  for(const change of [{base:'https://attacker.example'},{publicOrigin:'http://updates.example.test'},{apiKey:'short'},{certificate:key},{appId:'invalid'}]){
   writeFileSync(p,JSON.stringify({[app]:{...b,...change}}));assert.throws(()=>loadBindings(env),e=>!e.stack.includes(key));
  }
  writeFileSync(p,JSON.stringify({[app]:b,[xp]:b}));assert.throws(()=>loadBindings(env));
 }finally{rmSync(d,{recursive:true});}
});
