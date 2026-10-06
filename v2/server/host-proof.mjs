// Host acceptance only. Does not execute JS or claim Android activation.
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash, verify, X509Certificate } from 'node:crypto';

const [base, credentialsPath, receiptPath, stage] = process.argv.slice(2);
assert(['before', 'after', 'rollback', 'rollback-check'].includes(stage));
for (let attempt = 0; ; attempt++) {
  try { if ((await fetch(`${base}/ready`)).ok) break; } catch {}
  assert(attempt < 50, 'server did not become ready');
  await new Promise(resolve => setTimeout(resolve, 200));
}
const c = JSON.parse(await readFile(credentialsPath));
const runtime = 'dootah-v2-spike-1';
const expectedHash = 'XFvqGrkn6WAwNw8VBH9NTTnIYeBhia_a3Brhz9l2FtI';
const headers = { 'expo-app-id': c.appId, 'expo-channel-name': c.channel,
  'expo-platform': 'android', 'expo-runtime-version': runtime, 'expo-protocol-version': '1',
  'expo-expect-signature': 'sig, keyid="main", alg="rsa-v1_5-sha256"',
  'expo-embedded-update-id': 'abbbd47f-a56c-46e8-a121-f3f1d4b4ca3a' };
const auth = { Authorization: `Bearer ${c.apiKey}` };
const publicKey = new X509Certificate(c.certificate).publicKey;
const checks = {};
function parts(response, text) {
  const boundary = /boundary="?([^";]+)/i.exec(response.headers.get('content-type'))?.[1];
  assert(boundary, 'expected multipart Expo response');
  return text.split(`--${boundary}`).filter(s => s.includes('Content-Disposition:')).map(s => {
    const at = s.indexOf('\r\n\r\n');
    const h = s.slice(0, at);
    const body = s.slice(at + 4, -2);
    return { name: /name="([^"]+)"/.exec(h)[1], body,
      signature: /expo-signature:.*?sig="([^"]+)"/i.exec(h)?.[1] };
  });
}
async function manifest(overrides = {}) {
  const r = await fetch(`${base}/manifest`, { headers: { ...headers, ...overrides } });
  const text = await r.text();
  return { status: r.status, parts: r.ok && r.status !== 204 ? parts(r, text) : [], text };
}
function signed(part) {
  assert(part?.signature, 'signature missing');
  const sig = Buffer.from(part.signature, 'base64');
  assert(verify('RSA-SHA256', Buffer.from(part.body), publicKey, sig), 'invalid signature');
  assert(!verify('RSA-SHA256', Buffer.from(part.body + ' '), publicKey, sig), 'tampered body accepted');
  sig[0] ^= 1;
  assert(!verify('RSA-SHA256', Buffer.from(part.body), publicKey, sig), 'tampered signature accepted');
}
if (stage.startsWith('rollback')) {
  if (stage === 'rollback') {
    const q = new URLSearchParams({ runtimeVersion: runtime, platform: 'android' });
    const r = await fetch(`${base}/${c.appId}/rollback/${c.branch}?${q}`, { method: 'POST', headers: auth });
    checks.rollbackResponse = await r.text();
    assert.equal(r.status, 200, checks.rollbackResponse);
  }
  const rollback = await manifest();
  const part = rollback.parts.find(p => p.name === 'directive');
  signed(part);
  const directive = JSON.parse(part.body);
  assert.equal(directive.type, 'rollBackToEmbedded');
  const before = JSON.parse(await readFile(receiptPath));
  assert(new Date(directive.parameters.commitTime) > new Date(before.manifest.createdAt));
  if (stage === 'rollback-check') {
    const previous = JSON.parse(await readFile(`${receiptPath}.rollback.json`));
    assert.deepEqual(directive, previous.directive, 'rollback changed across restart');
    checks.rollbackRestartPersistence = true;
  }
  const oldAsset = await fetch(before.manifest.launchAsset.url, { headers });
  assert.equal(oldAsset.status, 200);
  assert.equal(createHash('sha256').update(Buffer.from(await oldAsset.arrayBuffer())).digest('base64url'), expectedHash);
  checks.directive = directive;
  checks.signatureAndTamperRejection = true;
  checks.previousAssetRetained = true;
} else {
  const response = await manifest();
  assert.equal(response.status, 200);
  const part = response.parts.find(p => p.name === 'manifest');
  signed(part);
  const m = JSON.parse(part.body);
  assert.equal(m.runtimeVersion, runtime);
  assert.equal(m.metadata.branch, c.branch);
  assert.equal(m.launchAsset.hash, expectedHash);
  const asset = await fetch(m.launchAsset.url, { headers });
  assert.equal(asset.status, 200);
  const data = Buffer.from(await asset.arrayBuffer());
  assert.equal(data.length, 1433590);
  assert.equal(createHash('sha256').update(data).digest('base64url'), expectedHash);
  data[0] ^= 1;
  assert.notEqual(createHash('sha256').update(data).digest('base64url'), expectedHash);
  checks.assetBytes = data.length;
  checks.signatureAndTamperRejection = true;
  checks.manifest = m;
  const branchOverride = await manifest({ 'xprem-branch': 'not-present' });
  for (const p of branchOverride.parts.filter(p => p.name === 'manifest')) {
    assert.equal(JSON.parse(p.body).metadata.branch, c.branch, 'unauthorized branch served');
  }
  checks.branchOverride = { status: branchOverride.status, allowedBranch: c.branch };
  for (const [name, override] of Object.entries({ app: { 'expo-app-id': '11111111-1111-4111-8111-111111111111' },
    runtime: { 'expo-runtime-version': 'not-installed' }, platform: { 'expo-platform': 'ios' },
    channel: { 'expo-channel-name': 'not-present' } })) {
    const rejected = await manifest(override);
    assert(!rejected.parts.some(p => p.name === 'manifest'), `${name} leaked manifest`);
    checks[name] = { status: rejected.status, body: rejected.parts.map(p => JSON.parse(p.body)) };
  }
  const assetWrongApp = await fetch(m.launchAsset.url, { headers: { ...headers, 'expo-app-id': '11111111-1111-4111-8111-111111111111' } });
  assert.equal(assetWrongApp.status, 404);
  checks.assetAppIsolation = true;
  const unauthorized = await fetch(`${base}/${c.appId}/rollback/${c.branch}?runtimeVersion=${runtime}&platform=android`, { method: 'POST' });
  assert.equal(unauthorized.status, 401);
  checks.publisherAuthentication = true;
  if (stage === 'after') {
    const before = JSON.parse(await readFile(receiptPath));
    assert.deepEqual(m, before.manifest, 'manifest changed across restart');
    checks.restartPersistence = true;
  } else {
    // Create a pending upload through upstream APIs, then corrupt its body. It must
    // not enter CAS or become a published release when finalize is attempted.
    const original = Buffer.from('Dootah negative upload integrity probe');
    const hash = createHash('sha256').update(original).digest('base64url');
    const q = new URLSearchParams({ runtimeVersion: runtime, platform: 'android' });
    const r = await fetch(`${base}/${c.appId}/requestUploadUrl/${c.branch}?${q}`, { method: 'POST',
      headers: { ...auth, 'Content-Type': 'application/json' }, body: JSON.stringify({ files: [{
        path: 'negative.hbc', hash, key: createHash('md5').update(original).digest('hex'), role: 'launch', ext: 'hbc' }] }) });
    assert.equal(r.status, 200);
    const pending = await r.json();
    assert.equal(pending.uploadRequests.length, 1);
    const item = pending.uploadRequests[0];
    const form = new FormData(); form.append('file', new Blob(['corrupt bytes']), 'negative.hbc');
    const corrupt = await fetch(item.requestUploadUrl, { method: 'PUT', headers: { ...item.headers, ...auth }, body: form });
    assert.equal(corrupt.status, 400);
    q.set('updateId', String(pending.updateId));
    const finalize = await fetch(`${base}/${c.appId}/markUpdateAsUploaded/${c.branch}?${q}`, { method: 'POST', headers: auth });
    assert.equal(finalize.status, 400);
    assert.equal(JSON.parse((await manifest()).parts.find(p => p.name === 'manifest').body).id, m.id);
    checks.corruptUploadRejected = { upload: corrupt.status, finalize: finalize.status, pendingUpdateId: pending.updateId };
  }
}
const destination = stage === 'before' ? receiptPath : `${receiptPath}.${stage}.json`;
await writeFile(destination, JSON.stringify({ stage, ...checks }, null, 2) + '\n', { flag: 'wx' });
console.log(`PASS ${stage}: ${destination}`);
