import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, readFile, chmod, rm, symlink, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { origin, readPrivate, writePrivate, client } from './auth.mjs';
import { customer } from './customer.mjs';
import { mintEnrollmentTicket } from './enrollment.mjs';
import { publishCloud } from './cloud.mjs';

async function fixture(run) {
  const directory = await mkdtemp(join(tmpdir(), 'dootah-cli-test-'));
  const calls = [];
  const secret = 'x'.repeat(43);
  const server = createServer(async (req, res) => {
    let raw = ''; for await (const chunk of req) raw += chunk;
    const body = raw ? JSON.parse(raw) : undefined;
    calls.push({ path: req.url, method: req.method, headers: req.headers, body });
    res.setHeader('Content-Type', 'application/json');
    if (req.url === '/v1/sessions') {
      res.setHeader('Set-Cookie', `dootah=${secret}; HttpOnly`);
      res.end(JSON.stringify({ csrf: 'csrf', userId: 'user' }));
    } else if (req.url.includes('/environments/')) res.end(JSON.stringify({ id: 'env', app_id: 'app', name: 'dev', runtime: 'runtime' }));
    else if (req.url.includes('/tokens') && req.method === 'POST' || req.url === '/v1/enrollment-tickets') res.end(JSON.stringify({ id: 'secret-id', secret }));
    else res.end(JSON.stringify({ id: 'operation', resource_id: 'release', status: 'pending', items: [], nextCursor: null }));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}`;
  const token = join(directory, 'token'); await writePrivate(token, secret);
  const args = ['--url', url, '--allow-local-http', '--token-file', token, '--org', 'org'];
  try { await run({ directory, calls, secret, url, token, args }); }
  finally { await new Promise(resolve => server.close(resolve)); await rm(directory, { recursive: true, force: true }); }
}

test('HTTPS and strict loopback origin checks', () => {
  assert.equal(origin('https://cloud.test'), 'https://cloud.test');
  for (const url of ['http://cloud.test', 'http://127.0.0.1.evil.test', 'https://user:pass@cloud.test', 'https://cloud.test/path', 'https://cloud.test?token=x'])
    assert.throws(() => origin(url, true));
  assert.throws(() => origin('http://127.0.0.1'));
  assert.equal(origin('http://127.0.0.1:1234', true), 'http://127.0.0.1:1234');
});
test('private files reject permissive modes, symlinks and overwrites', () => fixture(async ({ directory, token, secret }) => {
  assert.equal(await readPrivate(token), secret);
  assert.equal((await stat(token)).mode & 0o777, 0o600);
  await assert.rejects(writePrivate(token, 'overwrite'));
  const link = join(directory, 'link'); await symlink(token, link);
  await assert.rejects(readPrivate(link));
  await chmod(token, 0o644); await assert.rejects(readPrivate(token));
}));
test('login, origin binding, session CSRF and logout; credentials never in result', () => fixture(async ({ directory, url, secret, calls }) => {
  const password = join(directory, 'password'), session = join(directory, 'session');
  await writePrivate(password, 'not-a-real-password');
  const flags = ['--url', url, '--allow-local-http', '--session-file', session];
  const result = await customer(['login', '--email', 'owner@example.test', '--password-file', password, ...flags]);
  assert(!JSON.stringify(result).includes(secret));
  await customer(['org', 'create', '--name', 'Example', ...flags]);
  assert.equal(calls.at(-1).headers['x-csrf-token'], 'csrf');
  assert.equal(calls.at(-1).headers.origin, url);
  assert.equal(calls.at(-1).headers.cookie, `dootah=${secret}`);
  await assert.rejects(client({ url:'https://other.test', sessionFile:session }), /origin mismatch/);
  await customer(['logout', ...flags]);
  await assert.rejects(readFile(session));
}));
test('org/app lists, creation and environment filters use only supported customer APIs', () => fixture(async ({ args, calls }) => {
  for (const noun of ['org', 'app']) {
    await customer([noun, 'create', '--name', 'Customer', ...args]);
    assert.deepEqual(calls.at(-1).body, { name: 'Customer' });
    await customer([noun, 'list', ...args]);
    assert.equal(calls.at(-1).method, 'GET');
  }
  await customer(['env', 'list', '--app', 'app', '--after', 'cursor', ...args]);
  assert.equal(calls.at(-1).path, '/v1/environments?appId=app&after=cursor');
}));
test('release controls preserve optimistic version and rollback idempotency', () => fixture(async ({ args, calls }) => {
  await customer(['releases', '--environment', 'env', ...args]);
  await customer(['release', 'inspect', '--id', 'release', ...args]);
  for (const command of ['rollout', 'pause', 'resume', 'rollback']) {
    await customer([command, '--id', 'release', '--version', '3', ...(command === 'rollout' ? ['--percentage', '25'] : []),
      ...(command === 'rollback' ? ['--key', 'retry-key', '--reason', 'restore'] : []), ...args]);
    assert.equal(calls.at(-1).path, `/v1/releases/release/${command}`);
    assert.equal(calls.at(-1).body.version, 3);
    if (command === 'rollback') assert.equal(calls.at(-1).headers['idempotency-key'], 'retry-key');
  }
  await assert.rejects(customer(['rollout', '--id', 'r', '--version', '1', '--percentage', '101', ...args]));
  await assert.rejects(customer(['rollback', '--id', 'r', '--version', '1', ...args]));
}));
test('scoped token and one-use ticket output are private and redacted; backend helper is authenticated', () => fixture(async ({ directory, args, calls, secret, url, token }) => {
  const output = join(directory, 'new-token');
  const result = await customer(['token', 'create', '--scopes', 'app:read,release:publish', '--days', '1', '--app', 'app', '--environment', 'env', '--out', output, ...args]);
  assert(!JSON.stringify(result).includes(secret)); assert.equal(await readPrivate(output), secret);
  assert.deepEqual(calls.at(-1).body.scopes, ['app:read', 'release:publish']);
  await customer(['token', 'revoke', '--id', result.id, ...args]);
  assert.equal(calls.at(-1).method, 'DELETE');
  const ticket = join(directory, 'ticket');
  const enrolled = await customer(['enrollment', 'ticket', '--environment', 'env', '--out', ticket, ...args]);
  assert(!JSON.stringify(enrolled).includes(secret)); assert.equal(await readPrivate(ticket), secret);
  assert.deepEqual(await mintEnrollmentTicket({ url, allowLocalHttp:true, tokenFile:token }, 'env'), { ticket:secret, expiresInSeconds:600 });
  assert.equal(calls.at(-1).headers.authorization, `Bearer ${secret}`);
}));
test('publisher uses shared file authentication and rejects app/channel/runtime mismatch before publication', () => fixture(async ({ url, token, calls }) => {
  const config = { allowLocalHttp:true, cloud:{ url, tokenFile:token, organizationId:'org', environmentId:'env', idempotencyKey:'stable' } };
  const contract = { appId:'app', channel:'dev', runtimeVersion:'runtime' };
  for (const key of Object.keys(contract)) await assert.rejects(publishCloud(config, { ...contract, [key]:'wrong' }, {}));
  assert(calls.every(c => c.method === 'GET'));
  const receipt = await publishCloud(config, contract, { schema:'test' });
  assert.equal(receipt.releaseId, 'release'); assert.equal(calls.at(-1).headers['idempotency-key'], 'stable');
}));
