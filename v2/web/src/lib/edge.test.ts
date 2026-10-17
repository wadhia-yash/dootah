import { describe, it, expect, vi } from "vitest";
import { isCloudPath, proxy } from "./edge";

const env = { DOOTAH_UPSTREAM: "https://upstream.example", DOOTAH_EDGE_SECRET: "s".repeat(64) };
const site = "https://dootah.pages.dev";
const call = (path: string, init: RequestInit = {}, headers: Record<string, string> = {}) =>
  new Request(site + path, { ...init, headers: { "cf-connecting-ip": "203.0.113.7", ...headers } });
const upstream = (response = new Response("ok")) => vi.fn<typeof fetch>(() => Promise.resolve(response));
const sent = (transport: ReturnType<typeof upstream>) => {
  const [target, init] = transport.mock.calls[0];
  return { target: String(target), init: init!, headers: new Headers(init!.headers) };
};

describe("Pages edge proxy", () => {
  it("forwards only Cloud API and OTA delivery paths", () => {
    expect(["/v1/sessions", "/manifest", "/assets"].every(isCloudPath)).toBe(true);
    expect(["/", "/console", "/v1", "/assets/x", "/manifest/x", "/dashboard.js", "/live"].some(isCloudPath)).toBe(false);
  });

  it("keeps the browser Origin, cookie and CSRF token and adds the edge identity", async () => {
    const transport = upstream();
    await proxy(call("/v1/releases?after=x", { method: "POST", body: "{}" }, {
      origin: site, cookie: "dootah=abc", "x-csrf-token": "t", "content-type": "application/json",
    }), env, transport);
    const { target, init, headers } = sent(transport);
    expect(target).toBe("https://upstream.example/v1/releases?after=x");
    expect(init.redirect).toBe("manual");
    expect(headers.get("origin")).toBe(site);
    expect(headers.get("cookie")).toBe("dootah=abc");
    expect(headers.get("x-csrf-token")).toBe("t");
    expect(headers.get("x-dootah-edge")).toBe(env.DOOTAH_EDGE_SECRET);
    expect(headers.get("x-dootah-client-ip")).toBe("203.0.113.7");
  });

  it("replaces forged forwarding, edge and Cloudflare headers", async () => {
    const transport = upstream();
    await proxy(call("/manifest", {}, {
      "x-forwarded-for": "198.51.100.1", forwarded: "for=198.51.100.1", "x-real-ip": "198.51.100.1",
      "x-dootah-edge": "forged", "x-dootah-client-ip": "198.51.100.1", "cf-ray": "r", host: "evil.example",
    }), env, transport);
    const { headers } = sent(transport);
    for (const name of ["x-forwarded-for", "forwarded", "x-real-ip", "cf-ray", "cf-connecting-ip"])
      expect(headers.has(name)).toBe(false);
    expect(headers.get("x-dootah-edge")).toBe(env.DOOTAH_EDGE_SECRET);
    expect(headers.get("x-dootah-client-ip")).toBe("203.0.113.7");
  });

  it("returns upstream status, body and every Set-Cookie unchanged", async () => {
    const body = "--b\r\nsigned directive\r\n--b--";
    const reply = new Response(body, { status: 201, headers: { "content-type": 'multipart/mixed; boundary="b"' } });
    reply.headers.append("set-cookie", "dootah=x; HttpOnly; SameSite=Strict; Path=/; Secure");
    reply.headers.append("set-cookie", "other=y; Secure");
    const response = await proxy(call("/manifest"), env, upstream(reply));
    expect(response.status).toBe(201);
    expect(response.headers.get("content-type")).toBe('multipart/mixed; boundary="b"');
    expect(response.headers.getSetCookie()).toHaveLength(2);
    expect(await response.text()).toBe(body);
  });

  it("does not follow upstream redirects", async () => {
    const response = await proxy(call("/assets?x=1"), env, upstream(new Response(null, { status: 302, headers: { location: "https://elsewhere.example/" } })));
    expect(response.status).toBe(302);
    expect(response.headers.get("location")).toBe("https://elsewhere.example/");
  });

  it("fails closed when misconfigured, unattributable or unreachable", async () => {
    const transport = upstream();
    for (const bad of [{}, { ...env, DOOTAH_EDGE_SECRET: "short" }, { ...env, DOOTAH_UPSTREAM: "http://upstream.example" },
      { ...env, DOOTAH_UPSTREAM: "https://upstream.example/path" }, { ...env, DOOTAH_UPSTREAM: "https://u:p@upstream.example" }])
      expect((await proxy(call("/v1/apps"), bad, transport)).status).toBe(503);
    expect((await proxy(new Request(site + "/v1/apps"), env, transport)).status).toBe(400);
    expect((await proxy(call("/console"), env, transport)).status).toBe(404);
    expect(transport).not.toHaveBeenCalled();
    const down = vi.fn<typeof fetch>(() => Promise.reject(new Error("offline")));
    expect((await proxy(call("/v1/apps"), env, down)).status).toBe(502);
  });
});
