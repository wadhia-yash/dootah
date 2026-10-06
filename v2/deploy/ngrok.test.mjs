import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {join} from 'node:path';

const root = fileURLToPath(new URL('.', import.meta.url));
function docker(args) {
  const r = spawnSync('docker', args, {encoding: 'utf8', env: {...process.env,
    DOOTAH_CLOUD_ORIGIN: 'https://validation.example.test',
    DOOTAH_OPERATOR_EMAIL: 'operator@example.test', DOOTAH_SITE: 'unused.example.test',
    DOOTAH_SECRETS_DIR: '/nonexistent-validation-secrets',
    DOOTAH_CADDYFILE: join(root, 'Caddyfile'),
    // Hostile base defaults must not survive overlay merging.
    DOOTAH_BIND_IP: '0.0.0.0', DOOTAH_HTTP_PORT: '80', DOOTAH_HTTPS_PORT: '443'}});
  assert.equal(r.status, 0, r.stderr);
  return JSON.parse(r.stdout);
}

test('effective ngrok Compose publishes only loopback Caddy and preserves private services', () => {
  const config = docker(['compose', '-f', join(root, 'compose.yaml'), '-f',
    join(root, 'compose.ngrok.yaml'), '--profile', 'operator', 'config', '--format', 'json']);
  for (const [name, service] of Object.entries(config.services)) {
    if (name !== 'proxy') assert.deepEqual(service.ports ?? [], [], name + ' must not publish');
  }
  const proxy = config.services.proxy;
  assert.equal(proxy.ports.length, 1);
  assert.equal(proxy.ports[0].host_ip, '127.0.0.1');
  assert.equal(String(proxy.ports[0].published), '18080');
  assert.equal(proxy.ports[0].target, 80);
  assert.equal(proxy.ports[0].protocol, 'tcp');
  const mount = proxy.volumes.filter(v => v.target === '/etc/caddy/Caddyfile');
  assert.equal(mount.length, 1);
  assert.equal(mount[0].source, join(root, 'Caddyfile.ngrok'));
  assert.equal(mount[0].read_only, true);
  assert.deepEqual(Object.keys(config.services.cloud.networks).sort(), ['backend', 'ingress']);
  for (const name of ['xprem', 'postgres'])
    assert.deepEqual(Object.keys(config.services[name].networks), ['backend']);
  assert.equal(config.networks.backend.internal, true);
  assert.equal(config.networks.ingress.internal, true);
  assert.deepEqual(Object.keys(proxy.networks).sort(), ['edge', 'ingress']);
  assert.equal(config.services.cloud.environment.DOOTAH_XPREM_ORIGIN, 'http://xprem:3100');
});

test('ngrok Caddy configuration serves HTTP only and proxies exclusively to private Cloud', () => {
  const config = docker(['compose', '-f', join(root, 'compose.yaml'), '-f',
    join(root, 'compose.ngrok.yaml'), 'config', '--format', 'json']);
  const adapted = docker(['run', '--rm', '--network', 'none', '-v',
    join(root, 'Caddyfile.ngrok') + ':/etc/caddy/Caddyfile:ro',
    config.services.proxy.image, 'caddy', 'adapt', '--config', '/etc/caddy/Caddyfile']);
  assert.equal(adapted.admin.disabled, true);
  const servers = Object.values(adapted.apps.http.servers);
  assert.equal(servers.length, 1);
  assert.deepEqual(servers[0].listen, [':80']);
  assert.equal(servers[0].automatic_https.disable, true);
  const proxies = [];
  function visit(value) {
    if (!value || typeof value !== 'object') return;
    if (value.handler === 'reverse_proxy') proxies.push(value);
    for (const child of Object.values(value)) visit(child);
  }
  visit(servers[0]);
  assert.equal(proxies.length, 1);
  assert.deepEqual(proxies[0].upstreams, [{dial: 'cloud:3100'}]);
});
