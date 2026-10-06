import { isIPv4, isIPv6 } from "node:net";

// Client attribution for application-layer rate limits. The direct socket peer is the
// client unless it is a configured trusted reverse proxy; only then is the proxy-set
// X-Forwarded-For consulted. Forwarded and X-Real-IP are never read: Caddy passes them
// through from the Internet unchanged.

// Bare IPv4/IPv6 only (no port, brackets or zone). IPv4-mapped IPv6 is the IPv4 address.
export function parseAddress(text) {
  if (typeof text !== "string") return null;
  if (isIPv4(text))
    return { v6: false, value: text.split(".").reduce((n, x) => (n << 8n) | BigInt(x), 0n) };
  if (!isIPv6(text) || text.includes("%")) return null;
  let host;
  try {
    host = new URL(`http://[${text}]/`).hostname.slice(1, -1);
  } catch {
    return null;
  }
  const [head, tail] = host.split("::");
  const left = head ? head.split(":") : [];
  const right = tail ? tail.split(":") : [];
  const groups = [...left, ...Array(8 - left.length - right.length).fill("0"), ...right];
  const value = groups.reduce((n, x) => (n << 16n) | BigInt(parseInt(x, 16)), 0n);
  if (value >> 32n === 0xffffn) return { v6: false, value: value & 0xffffffffn };
  return { v6: true, value };
}

// IPv6 clients usually control at least a /64, so the key is that prefix; otherwise
// rotating the interface identifier would be a trivial bypass.
export function addressKey({ v6, value }) {
  if (!v6) return [24n, 16n, 8n, 0n].map((s) => (value >> s) & 255n).join(".");
  return [112n, 96n, 80n, 64n].map((s) => ((value >> s) & 0xffffn).toString(16)).join(":") + "::/64";
}

// Comma-separated addresses or CIDR networks. Absent/blank trusts nothing. Anything
// malformed, a network with host bits set, or a zero-length prefix is rejected.
export function trustedProxies(spec) {
  if (spec === undefined || spec.trim() === "") return [];
  return spec.split(",").map((entry) => {
    const [address, prefix, extra] = entry.trim().split("/");
    const ip = parseAddress(address);
    if (!ip || extra !== undefined) throw Error("trusted proxy");
    const bits = ip.v6 ? 128 : 32;
    const length = prefix === undefined ? bits : /^\d{1,3}$/.test(prefix) ? Number(prefix) : 0;
    if (length < 1 || length > bits) throw Error("trusted proxy");
    const shift = BigInt(bits - length);
    if (ip.value & ((1n << shift) - 1n)) throw Error("trusted proxy");
    return Object.freeze({ v6: ip.v6, shift, network: ip.value >> shift });
  });
}

const trusted = (ip, proxies) =>
  proxies.some((p) => p.v6 === ip.v6 && ip.value >> p.shift === p.network);

// Rightmost X-Forwarded-For entry that is not itself a trusted proxy. A malformed
// entry, an absent header or an all-trusted chain attributes the request to the peer.
export function clientAddress(req, proxies) {
  const peer = parseAddress(String(req.socket?.remoteAddress ?? "").replace(/%.*$/, ""));
  if (!peer) return "unknown";
  const header = req.headers["x-forwarded-for"];
  if (trusted(peer, proxies) && typeof header === "string") {
    const hops = header.split(",");
    for (let i = hops.length - 1; i >= 0; i--) {
      const hop = parseAddress(hops[i].trim());
      if (!hop) break;
      if (!trusted(hop, proxies)) return addressKey(hop);
    }
  }
  return addressKey(peer);
}
