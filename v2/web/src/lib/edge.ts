// Same-origin edge for Cloudflare Pages: the pages.dev origin serves the site and
// forwards only Cloud API and OTA delivery paths to the private upstream. Request and
// response bodies stream unchanged, so signed manifests and assets are not altered.
export interface EdgeEnv {
  DOOTAH_UPSTREAM?: string;
  DOOTAH_EDGE_SECRET?: string;
}

export const isCloudPath = (path: string) =>
  path === "/manifest" || path === "/assets" || path.startsWith("/v1/");

// Client-supplied forwarding and edge headers are never trusted upstream.
const stripped = new Set([
  "host",
  "forwarded",
  "x-forwarded-for",
  "x-forwarded-host",
  "x-forwarded-proto",
  "x-real-ip",
  "x-dootah-edge",
  "x-dootah-client-ip",
]);

const fail = (status: number, message: string) =>
  new Response(message, {
    status,
    headers: { "content-type": "text/plain", "cache-control": "no-store" },
  });

function upstreamOrigin(value: string | undefined) {
  try {
    const u = new URL(value ?? "");
    return u.protocol === "https:" && !u.username && !u.password && u.pathname === "/" && !u.search && !u.hash
      ? u.origin
      : undefined;
  } catch {
    return undefined;
  }
}

export async function proxy(request: Request, env: EdgeEnv, transport: typeof fetch = (input, init) => fetch(input, init)) {
  const url = new URL(request.url);
  if (!isCloudPath(url.pathname)) return fail(404, "Not found");
  const origin = upstreamOrigin(env.DOOTAH_UPSTREAM);
  const secret = env.DOOTAH_EDGE_SECRET ?? "";
  if (!origin || !/^[0-9a-f]{64}$/.test(secret)) return fail(503, "Edge not configured");
  // Cloudflare sets this from the TCP peer; without it per-client limits cannot hold.
  const client = request.headers.get("cf-connecting-ip");
  if (!client) return fail(400, "Client address unavailable");
  const headers = new Headers();
  request.headers.forEach((value, name) => {
    if (!stripped.has(name) && !name.startsWith("cf-")) headers.append(name, value);
  });
  headers.set("x-dootah-edge", secret);
  headers.set("x-dootah-client-ip", client);
  headers.set("ngrok-skip-browser-warning", "1");
  const hasBody = !["GET", "HEAD"].includes(request.method);
  // Existing proxy/Cloud envelope bound, enforced before spending tunnel transfer.
  let body: Uint8Array | undefined;
  if (hasBody) {
    const max = 131072;
    if (Number(request.headers.get('content-length') ?? 0) > max) return fail(413, 'Request too large');
    const reader = request.body?.getReader();
    const chunks: Uint8Array[] = [];
    let length = 0;
    try {
      while (reader) {
        const part = await reader.read();
        if (part.done) break;
        length += part.value.byteLength;
        if (length > max) { await reader.cancel(); return fail(413, 'Request too large'); }
        chunks.push(part.value);
      }
    } catch { return fail(400, 'Invalid request body'); }
    body = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.length; }
  }
  let response: Response;
  try {
    response = await transport(origin + url.pathname + url.search, {
      method: request.method,
      headers,
      redirect: "manual",
      ...(hasBody ? { body, duplex: "half" } : {}),
    } as RequestInit);
  } catch {
    return fail(502, "Cloud unavailable");
  }
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers: new Headers(response.headers),
  });
}
