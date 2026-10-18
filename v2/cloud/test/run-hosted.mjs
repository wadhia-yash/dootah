// Owns a brand-new loopback-only PostgreSQL container. Never reads production URLs.
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import pg from 'pg';
const root=resolve(import.meta.dirname,'../../..');
const name='dootah-hosted-test-'+randomBytes(5).toString('hex');
const dir=mkdtempSync(join(tmpdir(),name));
const password=randomBytes(32).toString('hex');
const run=(args)=>execFileSync('docker',args,{encoding:'utf8',stdio:['ignore','pipe','pipe']}).trim();
let db;
try {
  writeFileSync(join(dir,'password'),password,{mode:0o600});
  run(['run','-d','--rm','--name',name,'-p','127.0.0.1::5432','-v',dir+':/test:ro','-e','POSTGRES_PASSWORD_FILE=/test/password','postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24']);
  const port=run(['port',name,'5432/tcp']).split(':').at(-1);
  for(let n=0;n<60;n++) {
    try { run(['exec',name,'pg_isready','-h','127.0.0.1','-U','postgres']); break; }
    catch { if(n===59) throw Error('test database unavailable'); await new Promise(r=>setTimeout(r,500)); }
  }
  const owner=`postgresql://postgres:${password}@127.0.0.1:${port}/postgres`;
  db=new pg.Client({connectionString:owner});await db.connect();
  await db.query('CREATE ROLE dootah_cloud_runtime NOLOGIN');
  await db.query(`CREATE ROLE hosted_test_runtime LOGIN PASSWORD '${password}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS`);
  await db.query('GRANT dootah_cloud_runtime TO hosted_test_runtime');
  const ownerFile=join(dir,'owner-url'),runtimeFile=join(dir,'runtime-url');
  writeFileSync(ownerFile,owner,{mode:0o600});
  writeFileSync(runtimeFile,owner.replace('postgres:', 'hosted_test_runtime:'),{mode:0o600});
  // Do not inherit local configuration, database secrets or existing fixture pointers.
  const env=Object.fromEntries(Object.entries(process.env).filter(([k])=>!k.startsWith('DOOTAH_')&&!k.startsWith('DATABASE_URL')&&k!=='NODE_ENV'));
  Object.assign(env,{DOOTAH_MODE:'development',DOOTAH_ALLOW_LOCAL_HTTP:'true',DOOTAH_CLOUD_ORIGIN:'http://127.0.0.1:3100',
    DOOTAH_HOSTED_ALPHA:'true',DOOTAH_HOSTED_MONTHLY_REQUESTS:'1000',DOOTAH_HOSTED_MONTHLY_BYTES:'100000',DOOTAH_TEST_HOSTED_OWNER_FILE:ownerFile,DOOTAH_CLOUD_DATABASE_URL_FILE:ownerFile,
    DOOTAH_VALIDATOR_CLASSPATH:process.env.DOOTAH_TEST_VALIDATOR_CLASSPATH ?? join(root,'v2/cloud/build/validator')+':'+join(root,'v2/publishing/build/install/dootah-publishing/lib/*'),
    DOOTAH_JAVA:process.env.JAVA_HOME ? join(process.env.JAVA_HOME,'bin/java') : 'java'});
  function node(args) { const r=spawnSync(process.execPath,args,{cwd:root,env,stdio:'inherit'});if(r.status!==0)throw Error('hosted test command failed'); }
  node(['v2/cloud/admin.mjs','migrate']);node(['v2/cloud/admin.mjs','migrate']);
  env.DOOTAH_CLOUD_DATABASE_URL_FILE=runtimeFile;
  if (process.env.DOOTAH_HOSTED_REPORT) {
    const result=spawnSync(process.execPath,['--test','--test-reporter=tap','v2/cloud/test/hosted.test.mjs'],{cwd:root,env,encoding:'utf8'});
    writeFileSync(process.env.DOOTAH_HOSTED_REPORT,result.stdout,{mode:0o600});
    if(result.status!==0)throw Error('hosted tests failed; inspect private TAP report');
    console.log('Hosted PostgreSQL tests passed');
  } else node(['--test','v2/cloud/test/hosted.test.mjs']);
} finally {
  await db?.end();
  try { run(['rm','-f',name]); } catch {}
  rmSync(dir,{recursive:true,force:true});
}
