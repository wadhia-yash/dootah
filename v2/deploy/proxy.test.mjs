import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {join} from 'node:path';
import {configuration} from '../cloud/config.mjs';
import {clientAddress, parseAddress} from '../cloud/client.mjs';

const root = fileURLToPath(new URL('.', import.meta.url));
function docker(args, extra = {}) {
  const r = spawnSync('docker', args, {encoding: 'utf8', env: {...process.env,
    DOOTAH_CLOUD_ORIGIN: 'https://validation.example.test',
    DOOTAH_OPERATOR_EMAIL: 'operator@example.test', DOOTAH_SITE: 'unused.example.test',
    DOOTAH_SECRETS_DIR: '/nonexistent-validation-secrets',
    DOOTAH_CADDYFILE: join(root, 'Caddyfile'), DOOTAH_INGRESS_PREFIX: '', ...extra}});
  assert.equal(r.status, 0, r.stderr);
  return JSON.parse(r.stdout);
}
const inside = (address, cidr) => {
  const [net, bits] = cidr.split('/');
  const shift = BigInt(32 - Number(bits));
  return parseAddress(address).value >> shift === parseAddress(net).value >> shift;
};

test('production Compose trusts forwarded identity only from Caddy\'s pinned ingress address', () => {
  for (const [prefix, extra] of [['10.231.47', {}], ['10.99.3', {DOOTAH_INGRESS_PREFIX: '10.99.3'}]]) {
    const config = docker(['compose', '-f', join(root, 'compose.yaml'), '--profile', 'operator',
      'config', '--format', 'json'], extra);
    const ingress = config.networks.ingress;
    assert.equal(ingress.internal, true);
    assert.deepEqual(ingress.ipam.config, [{subnet: `${prefix}.0/28`, ip_range: `${prefix}.0/29`}]);
    const members = Object.entries(config.services).filter(([, s]) => s.networks && 'ingress' in s.networks);
    assert.deepEqual(members.map(([name]) => name).sort(), ['cloud', 'proxy']);
    const caddy = config.services.proxy.networks.ingress.ipv4_address;
    assert.equal(caddy, `${prefix}.14`);
    // Static Caddy address lies in the subnet but outside the dynamic range Cloud draws from.
    assert(inside(caddy, ingress.ipam.config[0].subnet) && !inside(caddy, ingress.ipam.config[0].ip_range));
    const env = config.services.cloud.environment;
    assert.equal(env.DOOTAH_TRUSTED_PROXIES, caddy);
    const proxies = configuration({...env, DOOTAH_CLOUD_DATABASE_URL_FILE: undefined,
      DOOTAH_CLOUD_DATABASE_URL: 'postgresql://runtime:' + 'x'.repeat(32) + '@postgres/cloud',
      DOOTAH_JAVA: '/java', DOOTAH_VALIDATOR_CLASSPATH: '/validator'}).trustedProxies;
    const at = (peer) => clientAddress({socket: {remoteAddress: peer}, headers: {'x-forwarded-for': '198.51.100.7'}}, proxies);
    assert.equal(at(caddy), '198.51.100.7');
    for (const other of [`${prefix}.1`, `${prefix}.2`, `${prefix}.13`, `${prefix}.15`]) assert.equal(at(other), other);
    for (const name of ['cloud-migrate', 'cloud-user']) assert.equal(config.services[name].environment.DOOTAH_TRUSTED_PROXIES, undefined);
  }
});

test('every Caddy configuration replaces X-Forwarded-For with its own client address', () => {
  const image = docker(['compose', '-f', join(root, 'compose.yaml'), 'config', '--format', 'json']).services.proxy.image;
  for (const file of ['Caddyfile', 'Caddyfile.local', 'Caddyfile.ngrok', 'Caddyfile.web']) {
    const adapted = docker(['run', '--rm', '--network', 'none', '-e', 'DOOTAH_SITE=ci.example.invalid', '-v',
      join(root, file) + ':/etc/caddy/Caddyfile:ro', image, 'caddy', 'adapt', '--config', '/etc/caddy/Caddyfile']);
    const proxies = [];
    (function visit(value) {
      if (!value || typeof value !== 'object') return;
      if (value.handler === 'reverse_proxy') proxies.push(value);
      for (const child of Object.values(value)) visit(child);
    })(adapted);
    assert.equal(proxies.length, 1, file);
    assert.deepEqual(proxies[0].headers.request.set, {'X-Forwarded-For': ['{http.vars.client_ip}']}, file);
    // Without trusted_proxies Caddy's client_ip is the TCP peer, never a client-sent header.
    for (const server of Object.values(adapted.apps.http.servers)) {
      assert.equal(server.trusted_proxies, undefined, file);
      assert.equal(server.client_ip_headers, undefined, file);
    }
  }
});

test('Neon override grants only Cloud egress and keeps the frontend on the same proxy', () => {
 const config = docker(['compose', '-f', join(root, 'compose.yaml'), '-f', join(root, 'compose.neon.yaml'), '-f', join(root, 'compose.web.yaml'), '--profile', 'operator', 'config', '--format', 'json'], {DOOTAH_WEB_DIST:'/nonexistent-web-dist'});
 assert.deepEqual(Object.keys(config.services.cloud.networks).sort(), ['backend','ingress','neon-egress']);
 assert.equal(config.services.cloud.depends_on?.postgres, undefined);
 assert.deepEqual(Object.keys(config.services.xprem.networks), ['backend']);
 assert.deepEqual(Object.keys(config.services.proxy.networks).sort(), ['edge','ingress']);
 for (const service of ['cloud','xprem','postgres']) assert.equal(config.services[service].ports, undefined);
 assert(config.services.proxy.volumes.some(v => v.target === '/srv/web' && v.read_only));
 assert.equal(config.services.cloud.environment.DOOTAH_CLOUD_DATABASE_URL_FILE, '/run/private/cloud-runtime-url');
});

test('operator helper uses the same optional Compose files as the running deployment', () => {
  const result = spawnSync('python3', ['-c', `
import sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
import ops
ops.COMPOSE_OVERRIDES[:] = [Path(sys.argv[1])/'compose.neon.yaml']
r = ops.compose(Path('/dev/null'), '--profile', 'operator', 'config', '--format', 'json', capture=True)
sys.stdout.buffer.write(r.stdout)
`, root], {encoding:'utf8', env:{...process.env,
    DOOTAH_CLOUD_ORIGIN:'https://validation.example.test', DOOTAH_SITE:'validation.example.test',
    DOOTAH_OPERATOR_EMAIL:'operator@example.test', DOOTAH_SECRETS_DIR:'/nonexistent-validation-secrets',
    DOOTAH_CADDYFILE:join(root,'Caddyfile')}});
  assert.equal(result.status, 0, result.stderr);
  const config = JSON.parse(result.stdout);
  for (const name of ['cloud','cloud-migrate','cloud-user']) assert('neon-egress' in config.services[name].networks);
});
