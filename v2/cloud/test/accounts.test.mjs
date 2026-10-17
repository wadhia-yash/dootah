// Opt in only against a disposable migrated database: creates real account/org rows.
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { pool } from '../core.mjs';
import { trustedProxies } from '../client.mjs';
const enabled = process.env.DOOTAH_TEST_ACCOUNTS === 'true';
let server;
after(async () => {
  if (server) await new Promise(resolve => server.close(resolve));
  await pool.end();
});
test('registration, persistent sessions, organization/app persistence and role boundaries', { skip: !enabled }, async () => {
  const { createServer } = await import('../server.mjs');
  const { configuration } = await import('../config.mjs');
  const origin = configuration().origin;
  server = createServer(trustedProxies('127.0.0.1'));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}/v1/`;
  let ip = 1;
  async function request(path, method = 'GET', body, auth = {}, extra = {}) {
    const response = await fetch(base + path, { method, headers: {
      Origin: origin, 'Content-Type': 'application/json',
      'X-Forwarded-For': `198.18.1.${ip}`, ...auth, ...extra,
    }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    return { status: response.status, body: await response.json(), cookie: response.headers.get('set-cookie')?.split(';')[0] };
  }
  const pass = randomBytes(24).toString('base64url');
  const email = `${randomUUID()}@acceptance.invalid`;
  assert.equal((await request('users', 'POST', { email, password: pass }, {}, { Origin: 'https://wrong.invalid' })).status, 403);
  assert.equal((await request('users', 'POST', { email, password: 'short' })).status, 422);
  assert.equal((await request('users', 'POST', { email: 'invalid', password: pass })).status, 422);
  assert.equal((await request('users', 'POST', { email, password: pass, role: 'Owner' })).status, 422);
  ip++;
  const account = await request('users', 'POST', { email, password: pass });
  assert.equal(account.status, 201);
  assert.equal((await request('users', 'POST', { email, password: pass })).status, 409);
  const stored = (await pool.query('SELECT password_hash FROM users WHERE id=$1', [account.body.id])).rows[0];
  assert.notEqual(stored.password_hash, pass);
  await assert.rejects(pool.query('UPDATE users SET email=email WHERE id=$1', [account.body.id]), e => e.code === '42501');
  await assert.rejects(pool.query('DELETE FROM users WHERE id=$1', [account.body.id]), e => e.code === '42501');
  assert.equal((await request('sessions', 'POST', { email, password: 'incorrect' })).status, 401);
  const login = await request('sessions', 'POST', { email, password: pass });
  assert.equal(login.status, 200);
  const auth = { Cookie: login.cookie, 'X-CSRF-Token': login.body.csrf };
  const restored = await request('sessions/current', 'GET', undefined, { Cookie: login.cookie });
  assert.equal(restored.status, 200);
  assert.equal(restored.body.csrf, login.body.csrf);
  assert.equal((await request('organizations', 'POST', { name: 'refused' }, { Cookie: login.cookie })).status, 403);
  const org = await request('organizations', 'POST', { name: 'Account acceptance' }, auth);
  assert.equal(org.status, 200);
  auth['Dootah-Organization'] = org.body.id;
  const app = await request('apps', 'POST', { name: 'Persisted application' }, auth);
  assert.equal(app.status, 200);
  assert.equal((await request(`apps/${app.body.id}`, 'GET', undefined, auth)).body.name, 'Persisted application');
  for (const role of ['Developer', 'Viewer']) {
    ip++;
    const memberEmail = `${randomUUID()}@acceptance.invalid`;
    assert.equal((await request('users', 'POST', { email: memberEmail, password: pass })).status, 201);
    assert.equal((await request('members', 'POST', { email: memberEmail, role }, auth)).status, 200);
    const member = await request('sessions', 'POST', { email: memberEmail, password: pass });
    const memberAuth = { Cookie: member.cookie, 'X-CSRF-Token': member.body.csrf, 'Dootah-Organization': org.body.id };
    assert.equal((await request(`apps/${app.body.id}`, 'GET', undefined, memberAuth)).status, 200);
    assert.equal((await request('apps', 'POST', { name: 'denied' }, memberAuth)).status, 403);
    assert.equal((await request('members', 'POST', { email, role: 'Owner' }, memberAuth)).status, 403);
    assert.equal((await request('apps', 'GET', undefined, memberAuth, { 'Dootah-Organization': randomUUID() })).status, 404);
  }
  assert.equal((await request('sessions/current', 'DELETE', undefined, auth)).status, 200);
  assert.equal((await request('sessions/current', 'GET', undefined, auth)).status, 401);
  const relogin = await request('sessions', 'POST', { email, password: pass });
  assert.equal(relogin.status, 200);
  assert.notEqual(relogin.cookie, login.cookie);
  const orgs = await request('organizations', 'GET', undefined, { Cookie: relogin.cookie });
  assert(orgs.body.items.some(o => o.id === org.body.id && o.role === 'Owner'));
  ip++;
  const bad = { email: 'bad', password: 'bad' };
  for (let i = 0; i < 5; i++) assert.equal((await request('users', 'POST', bad)).status, 422);
  assert.equal((await request('users', 'POST', bad)).status, 429);
});
