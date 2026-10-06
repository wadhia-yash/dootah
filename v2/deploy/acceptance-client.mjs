// Runs inside the Cloud container against the TLS proxy. Synthetic Portable IR is
// a deployment transport fixture; this does not claim source/compiler/device OTA proof.
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {randomUUID,verify,X509Certificate} from 'node:crypto';
import {pool,scopes} from '/app/cloud/core.mjs';
const base=process.env.DOOTAH_CLOUD_ORIGIN;
const mode=process.argv[2];
async function call(headers,path,method='GET',body) {
 const r=await fetch(base+'/v1/'+path,{method,headers:{'Content-Type':'application/json',...headers,'Idempotency-Key':randomUUID()},...(body?{body:JSON.stringify(body)}:{})});
 const b=await r.json();assert.equal(r.status,200,JSON.stringify(b));return b;
}
async function wait(a,op){
 for(let i=0;i<90;i++){
  const b=await call({Authorization:'Bearer '+a.token},'operations/'+op.id);
  if(b.status==='succeeded'){assert.equal(b.receipt.signatureVerified,true);return b;}
  assert.notEqual(b.status,'failed');await new Promise(r=>setTimeout(r,500));
 }throw Error('operation timeout');
}
const id='dth1:'+'a'.repeat(64);
const types=['String','Boolean','Int','String?','Boolean?','Int?'];
const capabilities={runtimeAbi:2,logicAbi:1,capabilities:{
 'logic.pure.v1':{version:1,effect:'pure',inputs:types,outputs:types,implementation:'PortableProgram/JavaScriptSandbox',permissions:[],limits:{operations:512,depth:24,stringUnits:256}},
 'compose.basicText.v1':{version:1,effect:'render',inputs:['String'],outputs:['Unit'],implementation:'ComposeDispatch/BasicText',permissions:[],limits:{stringUnits:256}}},
 limits:{payloadBytes:65536,entries:32,parameters:9,loops:0,recursion:0,collections:0,async:false}};
const artifact={schema:'dootah.portable',runtimeAbi:2,logicAbi:1,runtimeVersion:'dootah-v2-logic-2',requires:['logic.pure.v1','compose.basicText.v1'],overrides:[{functionId:id,parameters:[],expression:{op:'literal',type:'String',value:'Deployment acceptance'}}]};
try {
 if(mode==='setup'){
  const tenants={}; const password=(await readFile('/tmp/initial-password','utf8')).trim();
  for(const label of ['a','b']){
   const r=await fetch(base+'/v1/sessions',{method:'POST',headers:{Origin:base,'Content-Type':'application/json'},body:JSON.stringify({email:`owner-${label}@local.test`,password})});
   assert.equal(r.status,200);assert(r.headers.get('set-cookie').includes('; Secure'));
   const session=await r.json();const headers={Cookie:r.headers.get('set-cookie').split(';')[0],Origin:base,'X-CSRF-Token':session.csrf,'Content-Type':'application/json'};
   const org=(await call(headers,'organizations','POST',{name:'Acceptance '+label})).id;
   headers['Dootah-Organization']=org;
   const app=(await call(headers,'apps','POST',{name:'Deployment '+label})).id;
   const environment=(await call(headers,'environments','POST',{appId:app,name:'development',runtime:artifact.runtimeVersion,contract:{capabilities,functions:{[id]:[]}}})).id;
   const token=(await call(headers,'tokens','POST',{appId:app,environmentId:environment,scopes,expiresDays:1})).secret;
   tenants[label]={org,app,environment,token,headers};
  }
  await writeFile('/tmp/tenants.json',JSON.stringify(tenants),{mode:0o600});
  await writeFile('/tmp/artifact.json',JSON.stringify(artifact),{mode:0o600});
 } else {
  const tenants=JSON.parse(await readFile('/tmp/tenants.json'));
  if(mode==='publish'){
   const a=tenants.a,headers={Authorization:'Bearer '+a.token};
   const fresh=structuredClone(artifact);fresh.overrides[0].expression.value += ' '+randomUUID();
   const op=await call(headers,'releases','POST',{environmentId:a.environment,revision:'9d local deployment',artifact:fresh});
   await wait(a,op);
   let release=await call(headers,'releases/'+op.resource_id);
   await call(headers,`releases/${release.id}/rollout`,'POST',{version:release.version,percentage:100});
   release=await call(headers,'releases/'+release.id);
   await wait(a,await call(headers,`releases/${release.id}/rollback`,'POST',{version:release.version}));
  } else if(mode==='verify'){
   for(const a of Object.values(tenants)){
    assert.equal((await call({Authorization:'Bearer '+a.token},'apps/'+a.app)).id,a.app);
   }
   const a=tenants.a;
   const r=await fetch(base+'/manifest',{headers:{'expo-app-id':a.app,'expo-channel-name':'development','expo-runtime-version':artifact.runtimeVersion,'expo-platform':'android','expo-protocol-version':'1','eas-client-id':randomUUID(),'expo-embedded-update-id':randomUUID(),'expo-expect-signature':'sig, keyid="main", alg="rsa-v1_5-sha256"'}});
   assert.equal(r.status,200); const text=await r.text();assert(text.includes('rollBackToEmbedded'));assert(text.includes('expo-signature:'));
   const boundary=/boundary="?([^";]+)/i.exec(r.headers.get('content-type'))[1];
   const part=text.split('--'+boundary).find(s=>/name="directive"/.test(s));
   const at=part.indexOf('\r\n\r\n'),body=part.slice(at+4,-2),sig=/expo-signature:.*?sig="([^"]+)"/i.exec(part.slice(0,at))[1];
   const bindings=JSON.parse(await readFile(process.env.DOOTAH_DELIVERY_BINDINGS));
   assert(verify('RSA-SHA256',Buffer.from(body),new X509Certificate(bindings[a.app].certificate).publicKey,Buffer.from(sig,'base64')));
  } else throw Error('unknown mode');
 }
 console.log(JSON.stringify({event:'acceptance_client',mode,passed:true}));
} finally{await pool.end();}
