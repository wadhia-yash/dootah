import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {mkdtempSync, mkdirSync, rmSync, writeFileSync} from 'node:fs';
import {randomBytes} from 'node:crypto';
import {tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
import {join} from 'node:path';

const root = fileURLToPath(new URL('.', import.meta.url));
const run = (args, extra = {}) => spawnSync('docker', args, {encoding: 'utf8', env: {...process.env, ...extra}});
function docker(args, extra) {
  const r = run(args, extra);
  assert.equal(r.status, 0, r.stderr);
  return r.stdout;
}
const env = dir => ({DOOTAH_CLOUD_ORIGIN: 'https://validation.pages.dev', DOOTAH_OPERATOR_EMAIL: 'operator@example.test',
  DOOTAH_SITE: 'unused.example.test', DOOTAH_SECRETS_DIR: dir, DOOTAH_CADDYFILE: join(root, 'Caddyfile.edge'),
  DOOTAH_EDGE_DIR: join(dir, 'edge'), DOOTAH_TUNNEL_DIR: join(dir, 'ngrok'), DOOTAH_INGRESS_PREFIX: ''});
const compose = dir => JSON.parse(docker(['compose', '-f', join(root, 'compose.yaml'), '-f', join(root, 'compose.neon.yaml'),
  '-f', join(root, 'compose.neon-xprem.yaml'), '-f', join(root, 'compose.edge.yaml'), 'config', '--format', 'json'], env(dir)));
const image = () => compose('/nonexistent-validation-secrets').services.proxy.image;

test('edge origin publishes no host ports and the tunnel holds no environment secrets', () => {
  const services = compose('/nonexistent-validation-secrets').services;
  for (const [name, service] of Object.entries(services)) assert.equal(service.ports, undefined, name);
  assert.deepEqual(Object.keys(services.tunnel.networks), ['edge']);
  assert.equal(services.tunnel.environment, undefined);
  assert.equal(services.tunnel.read_only, true);
  assert.deepEqual(services.tunnel.cap_drop, ['ALL']);
  assert.ok(services.proxy.volumes.some(v => v.target === '/run/edge' && v.read_only));
});

test('only the edge-authenticated route proxies, and it alone sets X-Forwarded-For from the edge', () => {
  const adapted = JSON.parse(docker(['run', '--rm', '--network', 'none', '-v', join(root, 'Caddyfile.edge') + ':/etc/caddy/Caddyfile:ro',
    image(), 'caddy', 'adapt', '--config', '/etc/caddy/Caddyfile']));
  const proxies = [];
  (function visit(value) {
    if (!value || typeof value !== 'object') return;
    if (value.handler === 'reverse_proxy') proxies.push(value);
    for (const child of Object.values(value)) visit(child);
  })(adapted);
  assert.equal(proxies.length, 1);
  assert.deepEqual(proxies[0].headers.request.set, {'X-Forwarded-For': ['{http.request.header.X-Dootah-Client-Ip}']});
  assert.deepEqual(proxies[0].headers.request.delete.sort(), ['Ngrok-Skip-Browser-Warning', 'X-Dootah-Client-Ip', 'X-Dootah-Edge']);
  const text = JSON.stringify(adapted);
  assert.ok(text.includes('{file./run/edge/secret}') && text.includes('^[0-9a-f]{64}$'));
  for (const server of Object.values(adapted.apps.http.servers)) {
    assert.equal(server.trusted_proxies, undefined);
    assert.equal(server.client_ip_headers, undefined);
  }
});

test('requests without the exact edge secret never reach Cloud, including with an empty or missing secret file', (t) => {
  const dir = mkdtempSync(join(tmpdir(), 'dootah-edge-'));
  const net = 'dootah-edge-' + randomBytes(4).toString('hex');
  const names = [net + '-cloud', net + '-proxy'];
  t.after(() => { run(['rm', '-f', ...names]); run(['network', 'rm', net]); rmSync(dir, {recursive: true, force: true}); });
  const secret = randomBytes(32).toString('hex');
  mkdirSync(join(dir, 'edge'));
  writeFileSync(join(dir, 'edge', 'secret'), secret);
  writeFileSync(join(dir, 'echo'), '{\n admin off\n auto_https off\n}\n:3100 {\n respond "xff={header.X-Forwarded-For} edge={header.X-Dootah-Edge}" 200\n}\n');
  docker(['network', 'create', '--internal', net]);
  docker(['run', '-d', '--name', names[0], '--network', net, '--network-alias', 'cloud', '-v', join(dir, 'echo') + ':/etc/caddy/Caddyfile:ro', image()]);
  docker(['run', '-d', '--name', names[1], '--network', net, '-v', join(root, 'Caddyfile.edge') + ':/etc/caddy/Caddyfile:ro',
    '-v', join(dir, 'edge') + ':/run/edge:ro', image()]);
  const get = (...headers) => {
    for (let i = 0; i < 20; i++) {
      const r = run(['exec', names[1], 'wget', '-qS', '-O-', ...headers.flatMap(h => ['--header', h]), 'http://127.0.0.1/v1/x']);
      const out = r.stdout + r.stderr;
      if (/HTTP\/1\.1 \d+/.test(out)) return {status: Number(/HTTP\/1\.1 (\d+)/.exec(out)[1]), body: r.stdout};
      spawnSync('sleep', ['0.5']);
    }
    throw Error('proxy unavailable');
  };
  const ok = get('X-Dootah-Edge: ' + secret, 'X-Dootah-Client-IP: 203.0.113.7', 'X-Forwarded-For: 198.51.100.9');
  assert.equal(ok.status, 200);
  assert.equal(ok.body, 'xff=203.0.113.7 edge=');
  assert.equal(get().status, 403);
  assert.equal(get('X-Dootah-Edge: ' + randomBytes(32).toString('hex')).status, 403);
  assert.equal(get('X-Dootah-Edge: ' + secret.toUpperCase()).status, 403);
  assert.equal(get('X-Dootah-Edge: ' + secret.slice(0, 32)).status, 403);
  writeFileSync(join(dir, 'edge', 'secret'), '');
  assert.equal(get('X-Dootah-Edge: ').status, 403);
  assert.equal(get('X-Dootah-Edge: ' + secret).status, 403);
  rmSync(join(dir, 'edge', 'secret'));
  assert.equal(get('X-Dootah-Edge: ').status, 403);
  assert.equal(get('X-Dootah-Edge: ' + secret).status, 403);
});
