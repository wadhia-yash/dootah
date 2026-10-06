import { test } from "node:test";
import assert from "node:assert/strict";
import { clientAddress, trustedProxies } from "../client.mjs";
import { configuration } from "../config.mjs";

const proxy = trustedProxies("10.231.47.14");
const from = (peer, headers = {}) => ({ socket: { remoteAddress: peer }, headers });

test("trusted proxy forwards distinct client identities", () => {
  assert.equal(clientAddress(from("10.231.47.14", { "x-forwarded-for": "198.51.100.7" }), proxy), "198.51.100.7");
  assert.equal(clientAddress(from("10.231.47.14", { "x-forwarded-for": "203.0.113.9" }), proxy), "203.0.113.9");
  // Node presents IPv4 peers of a dual-stack listener as IPv4-mapped IPv6.
  assert.equal(clientAddress(from("::ffff:10.231.47.14", { "x-forwarded-for": "198.51.100.7" }), proxy), "198.51.100.7");
  const network = trustedProxies("10.231.47.8/29, 2001:db8:ffff::/48");
  assert.equal(clientAddress(from("10.231.47.15", { "x-forwarded-for": "198.51.100.7" }), network), "198.51.100.7");
  assert.equal(clientAddress(from("2001:db8:ffff::5", { "x-forwarded-for": "203.0.113.9" }), network), "203.0.113.9");
});

test("forged forwarding headers from an untrusted peer are ignored", () => {
  const forged = {
    "x-forwarded-for": "198.51.100.7",
    forwarded: "for=198.51.100.8",
    "x-real-ip": "198.51.100.9",
  };
  for (const proxies of [[], proxy, trustedProxies("10.231.47.0/29")])
    assert.equal(clientAddress(from("203.0.113.50", forged), proxies), "203.0.113.50");
  // Forwarded and X-Real-IP are not proxy-controlled, even from the trusted proxy.
  const { "x-forwarded-for": _, ...other } = forged;
  assert.equal(clientAddress(from("10.231.47.14", other), proxy), "10.231.47.14");
});

test("absent, malformed and multiple forwarded values resolve deterministically", () => {
  const via = (value) => clientAddress(from("10.231.47.14", { "x-forwarded-for": value }), proxy);
  assert.equal(clientAddress(from("10.231.47.14"), proxy), "10.231.47.14");
  assert.equal(clientAddress(from("198.51.100.7"), []), "198.51.100.7");
  for (const bad of ["", " ", "unknown", "198.51.100.7:443", "[2001:db8::1]", "198.51.100.07",
    "198.51.100.256", "2001:db8::1%eth0", "198.51.100.7 198.51.100.8", "198.51.100.7,", "0x7f.1"])
    assert.equal(via(bad), "10.231.47.14", bad);
  // Rightmost untrusted hop wins; earlier client-supplied hops cannot select the key.
  assert.equal(via("192.0.2.1, 198.51.100.7"), "198.51.100.7");
  assert.equal(via("192.0.2.1,198.51.100.7, 10.231.47.14"), "198.51.100.7");
  assert.equal(via("garbage, 198.51.100.7"), "198.51.100.7");
  assert.equal(via("10.231.47.14, 10.231.47.14"), "10.231.47.14");
  assert.equal(clientAddress({ socket: {}, headers: {} }, proxy), "unknown");
});

test("IPv4 and IPv6 representations cannot fan out one client", () => {
  const via = (value) => clientAddress(from("10.231.47.14", { "x-forwarded-for": value }), proxy);
  assert.equal(via("::ffff:198.51.100.7"), "198.51.100.7");
  assert.equal(via("::ffff:c633:6407"), "198.51.100.7");
  const key = "2001:db8:1:2::/64";
  for (const same of ["2001:db8:1:2::1", "2001:DB8:1:2:0:0:0:1", "2001:0db8:0001:0002::ffff",
    "2001:db8:1:2:aaaa:bbbb:cccc:dddd", "2001:db8:1:2::198.51.100.7"])
    assert.equal(via(same), key, same);
  assert.notEqual(via("2001:db8:1:3::1"), key);
  assert.equal(clientAddress(from("2001:db8:1:2::9"), []), key);
  assert.equal(clientAddress(from("fe80::1%eth0"), []), "fe80:0:0:0::/64");
});

test("trusted proxy configuration is explicit and fails closed", () => {
  for (const blank of [undefined, "", "  "]) assert.deepEqual(trustedProxies(blank), []);
  for (const bad of ["proxy", "caddy:443", "10.231.47.14/33", "10.231.47.0/24/1", "10.231.47.5/28",
    "0.0.0.0/0", "::/0", "10.231.47.14,", "10.231.47.14/", "10.231.47.14/-1", "2001:db8::/129", "*"])
    assert.throws(() => trustedProxies(bad), bad);
  const local = { DOOTAH_ALLOW_LOCAL_HTTP: "true" };
  for (const bad of ["0.0.0.0/0", "proxy"])
    assert.throws(() => configuration({ ...local, DOOTAH_TRUSTED_PROXIES: bad }), /DOOTAH_TRUSTED_PROXIES/);
  assert.deepEqual(configuration(local).trustedProxies, []);
  assert.equal(configuration({ ...local, DOOTAH_TRUSTED_PROXIES: "10.231.47.14" }).trustedProxies.length, 1);
});
