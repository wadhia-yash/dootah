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
  for (const file of ['Caddyfile', 'Caddyfile.local', 'Caddyfile.ngrok']) {
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
