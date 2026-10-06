import assert from 'node:assert/strict';
import { parseArgs } from 'node:util';
import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises';
import { resolve, dirname, join, relative, isAbsolute } from 'node:path';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';
import { client, origin, readPrivate, writePrivate } from './auth.mjs';
import { analyzer } from './tooling.mjs';

const read = async path => JSON.parse(await readFile(path, 'utf8'));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { mode: 0o600 });
function inside(root, path) {
  assert(typeof path === 'string' && !isAbsolute(path), 'Record path must be relative');
  const file = resolve(root, path);
  assert(!relative(root, file).startsWith('..'), 'Record path escapes release');
  return file;
}
export async function loadConfig(path) {
  const config = await read(path), base = dirname(resolve(path));
  for (const key of ['apk', 'classes', 'classHashes', 'contract', 'sourceRoot'])
    if (config[key]) config[key] = resolve(base, config[key]);
  for (const key of ['tokenFile', 'sessionFile'])
    if (config.cloud?.[key]) config.cloud[key] = resolve(base, config.cloud[key]);
  return config;
}
export async function importRecord(recordPath, sourceRoot, configPath) {
  const record = await read(recordPath), base = dirname(resolve(recordPath));
  assert(record.schema === 1 && record.runtimeAbi === 2 && record.logicAbi === 1, 'Retained release ABI mismatch');
  const apk = inside(base, record.apk);
  assert.equal(hash(await readFile(apk)), record.apkSha256, 'Retained APK hash mismatch');
  const snapshot = inside(base, record.sources);
  for (const [path, digest] of Object.entries(record.sourceHashes))
    assert.equal(hash(await readFile(inside(snapshot, path))), digest, 'Retained source hash mismatch');
  const config = { apk, classes: inside(base, record.classes), classHashes: inside(base, record.classHashes),
    sourceRoot: snapshot, sourcePaths: Object.keys(record.sourceHashes).sort(),
    sourceRoots: record.sourceRoots,
    contract: resolve(dirname(configPath), 'installed-contract.json'), outputRoot: 'build/dootah-publish' };
  const work = await mkdtemp(join(tmpdir(), 'dootah-import-'));
  try {
    const input = join(work, 'input.json');
    await save(input, config);
    const output = join(work, 'contract.json');
    analyzer(['import', input, output]);
    const contract = await read(output);
    assert.equal(contract.apkSha256, record.apkSha256, 'Imported APK differs');
    for (const key of ['appId', 'channel', 'runtimeVersion'])
      assert.equal(contract[key], record.metadata[key], `Retained ${key} mismatch`);
    assert.equal(contract.packageName, record.metadata.applicationId, 'Application ID mismatch');
    const capabilities = await read(inside(base, record.capabilities));
    assert.deepEqual(contract.capabilities, capabilities, 'Retained capability mismatch');
    config.sourceRoot = resolve(sourceRoot);
    for (const key of ['apkSha256', 'runtimeVersion', 'packageName', 'appId', 'channel']) config[key] = contract[key];
    const bytes = await readFile(output);
    config.contractSha256 = hash(bytes);
    // Never silently replace an existing trusted baseline or publisher configuration.
    await writeFile(config.contract, bytes, { flag: 'wx', mode: 0o600 });
    await writeFile(configPath, JSON.stringify(config, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    return { config: resolve(configPath), installedFunctions: contract.functions.filter(f => f.installed).length };
  } finally { await rm(work, { recursive: true, force: true }); }
}
export async function registerEnvironment(call, config, overrides = {}) {
  const bytes = await readFile(config.contract), installed = JSON.parse(bytes);
  assert.equal(hash(bytes), config.contractSha256, 'Installed contract digest mismatch');
  assert.equal(hash(await readFile(config.apk)), installed.apkSha256, 'Retained APK mismatch');
  for (const [key, actual] of [['appId', overrides.app], ['channel', overrides.name], ['runtimeVersion', overrides.runtime]])
    if (actual !== undefined) assert.equal(actual, installed[key], `Installed ${key} mismatch`);
  const work = await mkdtemp(join(tmpdir(), 'dootah-contract-'));
  let contract;
  try {
    const input = join(work, 'config.json'), output = join(work, 'cloud-contract.json');
    await save(input, config);
    analyzer(['cloud-contract', input, output]);
    contract = await read(output);
  } finally { await rm(work, { recursive: true, force: true }); }
  const app = await call(`/v1/apps/${encodeURIComponent(installed.appId)}`);
  assert.equal(app.id, installed.appId, 'Cloud app mismatch');
  const find = async () => {
    let after;
    do {
      const query = new URLSearchParams({ appId: app.id, limit: '100', ...(after ? { after } : {}) });
      const page = await call('/v1/environments?' + query);
      const match = page.items.find(e => e.name === installed.channel);
      if (match) return match;
      after = page.nextCursor;
    } while (after);
  };
  const verify = env => {
    assert.equal(env.app_id, installed.appId, 'Cloud app mismatch');
    assert.equal(env.runtime, installed.runtimeVersion, 'Cloud runtime mismatch');
    assert.deepEqual(env.contract, contract, 'Existing environment has a different installed contract; use a new environment/runtime');
    if (config.cloud?.environmentId) assert.equal(env.id, config.cloud.environmentId, 'Cloud environment mismatch');
    return env;
  };
  let environment = await find();
  if (environment) return { ...verify(environment), alreadyRegistered: true };
  assert(!config.cloud?.environmentId, 'Configured environment does not match retained channel');
  try {
    const created = await call('/v1/environments', 'POST', { appId: app.id, name: installed.channel,
      runtime: installed.runtimeVersion, contract });
    environment = await call(`/v1/environments/${created.id}`);
  } catch (error) {
    environment = await find(); // Concurrent create is safe only if its complete contract agrees.
    if (!environment) throw error;
  }
  return verify(environment);
}

export const help = `Dootah alpha — JDK 21 and Node 24

  login --email EMAIL --password-file FILE --session-file FILE
  logout --session-file FILE
  org list | org create --name NAME
  app list | app create --name NAME
  env list [--app UUID]
  env create --config FILE                 Create and register retained installed contract
  contract register --config FILE          Same operation; verifies existing registration
  release import --record RELEASE_JSON --source-root APP_MODULE --config NEW_FILE
  analyze|bundle|publish CONFIG_JSON [--token-file FILE] [--revision TEXT] [--key KEY]
  releases [--app UUID] [--environment UUID] [--after CURSOR]
  release inspect --id UUID | operation inspect --id UUID
  rollout --id UUID --version N --percentage 0..100
  pause|resume --id UUID --version N
  rollback --id UUID --version N --key UUID [--reason TEXT]
  token list | token create --scopes CSV --days N [--app UUID] [--environment UUID] --out FILE
  token revoke --id UUID
  enrollment ticket --environment UUID --out FILE

Cloud options: --url ORIGIN --org UUID --session-file FILE or --token-file FILE
Environment inputs: DOOTAH_CLOUD_URL, DOOTAH_ORGANIZATION, DOOTAH_CLOUD_TOKEN.
--allow-local-http permits loopback only. Secret files must be private (0600).
No credential is printed. Login/token/ticket output files are explicit and never overwritten.
Native build/retention: ./gradlew :app:dootahRetainDebugRelease (or …ReleaseRelease).
Archive that private record before another native build. See ONBOARDING.md for integration.`;

export async function customer(argv) {
  const names = ['url', 'org', 'session-file', 'token-file', 'email', 'password-file', 'name', 'app', 'environment',
    'config', 'record', 'source-root', 'id', 'version', 'percentage', 'key', 'reason', 'scopes', 'days', 'out', 'after', 'runtime'];
  const { values: v, positionals: p } = parseArgs({ args: argv, allowPositionals: true,
    options: { ...Object.fromEntries(names.map(n => [n, { type: 'string' }])), 'allow-local-http': { type: 'boolean', default: false } } });
  const [command, action] = p;
  assert(p.length <= 2, 'Unexpected positional arguments');
  const need = key => { assert(v[key], `--${key} is required`); return v[key]; };
  const number = key => { const value = need(key); assert(/^\d+$/.test(value), `--${key} requires an integer`); return Number(value); };
  if (command === 'release' && action === 'import')
    return importRecord(resolve(need('record')), need('source-root'), resolve(need('config')));
  let config = v.config ? await loadConfig(v.config) : null;
  const auth = { ...config?.cloud, url: v.url ?? config?.cloud?.url ?? process.env.DOOTAH_CLOUD_URL,
    organizationId: v.org ?? config?.cloud?.organizationId ?? process.env.DOOTAH_ORGANIZATION,
    sessionFile: v['session-file'] ?? config?.cloud?.sessionFile, tokenFile: v['token-file'] ?? config?.cloud?.tokenFile,
    allowLocalHttp: v['allow-local-http'] || config?.allowLocalHttp === true };
  if (command === 'login') {
    const base = origin(auth.url, auth.allowLocalHttp);
    const password = v['password-file'] ? await readPrivate(v['password-file']) : process.env.DOOTAH_CLOUD_PASSWORD;
    assert(password, 'Supply --password-file or DOOTAH_CLOUD_PASSWORD; never a password argument');
    need('session-file');
    const response = await fetch(base + '/v1/sessions', { method: 'POST', redirect: 'error', signal: AbortSignal.timeout(15000),
      headers: { Origin: base, 'Content-Type': 'application/json' }, body: JSON.stringify({ email: need('email'), password }) });
    assert(response.ok, `Cloud login rejected (${response.status})`);
    const body = await response.json(), cookie = response.headers.get('set-cookie')?.split(';')[0];
    assert(cookie?.startsWith('dootah=') && body.csrf, 'Invalid session response');
    await writePrivate(v['session-file'], { origin: base, cookie, csrf: body.csrf, expiresAt: new Date(Date.now() + 8 * 3600000).toISOString() });
    return { authenticated: true, userId: body.userId };
  }
  const call = await client(auth);
  if (command === 'logout') {
    assert(auth.sessionFile, 'Logout requires --session-file');
    const result = await call('/v1/sessions/current', 'DELETE');
    await rm(auth.sessionFile);
    return result;
  }
  if (command === 'env' && action === 'create' || command === 'contract' && action === 'register') {
    assert(config, '--config is required');
    const env = await registerEnvironment(call, config, v);
    config.cloud = { url: auth.url, organizationId: auth.organizationId, environmentId: env.id,
      tokenEnvironment: config.cloud?.tokenEnvironment ?? 'DOOTAH_CLOUD_TOKEN' };
    config.allowLocalHttp = auth.allowLocalHttp;
    await save(resolve(v.config), config);
    return { id: env.id, appId: env.app_id, runtime: env.runtime, alreadyRegistered: env.alreadyRegistered ?? false };
  }
  if (['org', 'app', 'env', 'token'].includes(command)) {
    const route = { org: 'organizations', app: 'apps', env: 'environments', token: 'tokens' }[command];
    if (action === 'list') {
      const query = new URLSearchParams({ ...(v.app ? { appId: v.app } : {}), ...(v.after ? { after: v.after } : {}) });
      return call(`/v1/${route}?${query}`);
    }
    if (action === 'create' && ['org', 'app'].includes(command)) return call(`/v1/${route}`, 'POST', { name: need('name') });
    if (command === 'token' && action === 'revoke') return call(`/v1/tokens/${encodeURIComponent(need('id'))}`, 'DELETE');
    if (command === 'token' && action === 'create') {
      need('out');
      const result = await call('/v1/tokens', 'POST', { scopes: need('scopes').split(','), expiresDays: number('days'),
        ...(v.app ? { appId: v.app } : {}), ...(v.environment ? { environmentId: v.environment } : {}) });
      await writePrivate(v.out, result.secret);
      return { id: result.id, saved: resolve(v.out) };
    }
  }
  if (command === 'enrollment' && action === 'ticket') {
    need('out');
    const result = await call('/v1/enrollment-tickets', 'POST', { environmentId: need('environment') });
    await writePrivate(v.out, result.secret);
    return { id: result.id, saved: resolve(v.out), expiresInSeconds: 600, singleUse: true };
  }
  if (command === 'releases') {
    const query = new URLSearchParams({ ...(v.app ? { appId: v.app } : {}), ...(v.environment ? { environmentId: v.environment } : {}),
      ...(v.after ? { after: v.after } : {}) });
    return call('/v1/releases?' + query);
  }
  if (['release', 'operation'].includes(command) && action === 'inspect') return call(`/v1/${command}s/${encodeURIComponent(need('id'))}`);
  if (['rollout', 'pause', 'resume', 'rollback'].includes(command)) {
    const body = { version: number('version') };
    if (command === 'rollout') { body.percentage = number('percentage'); assert(body.percentage <= 100, 'Percentage must be 0..100'); }
    if (command === 'rollback' && v.reason) body.reason = v.reason;
    return call(`/v1/releases/${encodeURIComponent(need('id'))}/${command}`, 'POST', body,
      command === 'rollback' ? { 'Idempotency-Key': need('key') } : {});
  }
  throw Error('Unknown command. Run dootah --help.');
}
