import assert from 'node:assert/strict';
import { open } from 'node:fs/promises';
import { constants } from 'node:fs';

export function origin(value, local = false) {
  const url = new URL(value);
  assert(!url.username && !url.password && !url.search && !url.hash && url.pathname === '/', 'Cloud URL must be an origin');
  assert(url.protocol === 'https:' || (local && url.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)), 'Cloud requires HTTPS; local HTTP accepts loopback only');
  return url.origin;
}
export async function readPrivate(path) {
  const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const stat = await file.stat();
    assert(stat.isFile() && !(stat.mode & 0o077) && stat.uid === process.getuid?.(), 'Credential file must be owned by you with mode 0600');
    assert(stat.size <= 16384, 'Credential file too large');
    return (await file.readFile('utf8')).trim();
  } finally { await file.close(); }
}
export async function writePrivate(path, value) {
  assert(path, 'Provide an explicit private output file');
  const file = await open(path, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
  try { await file.writeFile(typeof value === 'string' ? value + '\n' : JSON.stringify(value) + '\n'); }
  finally { await file.close(); }
}
export async function client(config) {
  assert(!(config.sessionFile && config.tokenFile), 'Choose session-file or token-file authentication');
  const base = origin(config.url, config.allowLocalHttp);
  const headers = { 'Content-Type': 'application/json' };
  if (config.organizationId) headers['Dootah-Organization'] = config.organizationId;
  if (config.sessionFile) {
    const session = JSON.parse(await readPrivate(config.sessionFile));
    assert.equal(session.origin, base, 'Session origin mismatch');
    assert(Date.parse(session.expiresAt) > Date.now(), 'Session expired; log in again');
    headers.Cookie = session.cookie;
    headers.Origin = base;
    headers['X-CSRF-Token'] = session.csrf;
  } else {
    const token = config.tokenFile ? await readPrivate(config.tokenFile) : process.env[config.tokenEnvironment ?? 'DOOTAH_CLOUD_TOKEN'];
    assert(token, 'Supply DOOTAH_CLOUD_TOKEN, --token-file or --session-file');
    headers.Authorization = `Bearer ${token}`;
  }
  return async (path, method = 'GET', body, extra = {}) => {
    assert(path.startsWith('/v1/') && !path.includes('#'), 'Invalid API path');
    const response = await fetch(new URL(path, base), { method, headers: { ...headers, ...extra },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }), redirect: 'error', signal: AbortSignal.timeout(30000) });
    // Never echo server bodies or request headers: either may contain credentials.
    assert(response.ok, `Cloud request rejected (${response.status})`);
    return response.json();
  };
}
