# Cloudflare Pages edge for a Cloud without a custom domain

The Pages site's `*.pages.dev` origin is the single public origin for the website,
console, Cloud API, CLI and OTA delivery. A Pages Function forwards only `/v1/*`,
`/manifest` and `/assets` to a private upstream. Everything else is static.

```text
browser / CLI / Android -> https://PROJECT.pages.dev  (Pages static + Function)
                         -> HTTPS tunnel endpoint (not published)
                         -> tunnel agent -> Caddy (Caddyfile.edge) -> Cloud :3100
                                                                  -> private xprem
```

## Security properties

- **Same origin.** Set `DOOTAH_CLOUD_ORIGIN=https://PROJECT.pages.dev`. Cloud's host-only
  `HttpOnly; SameSite=Strict; Secure` cookie, exact-`Origin` and CSRF checks are unchanged.
  The Function forwards `Origin`, `Cookie` and `X-CSRF-Token` as sent.
- **Signed delivery.** xprem's `BASE_URL` and binding `publicOrigin` are the Pages origin, so
  manifests reference Pages asset URLs. Bodies stream through unmodified, and redirects are
  not followed.
- **Stable OTA URL.** APKs embed `https://PROJECT.pages.dev/manifest`. Replacing the upstream
  only changes the Function's `DOOTAH_UPSTREAM`; no APK rebuild is needed.
- **No bypass.** The Function adds `X-Dootah-Edge` (a 64-hex secret).
  - `Caddyfile.edge` answers 403 unless the header exactly matches a private tmpfs file.
  - An empty or missing secret file never matches.
  - The proxy publishes no host ports. The tunnel agent reaches it on the internal edge network.
- **Client IP.** The Function drops client `X-Forwarded-For`, `Forwarded`, `X-Real-IP` and all
  `cf-*` headers, and sends Cloudflare's `CF-Connecting-IP` as `X-Dootah-Client-IP`.
  - Only an authenticated request has that value copied to `X-Forwarded-For`.
  - Cloud still trusts forwarding only from Caddy's pinned address, so per-client rate limits hold.
- **Fail closed.** A missing or short secret, a non-HTTPS upstream or a missing client address
  return 503/400 before any upstream request. Upstream failure returns 502.

## Limits

Provider request/transfer budgets depend on the current account and must be checked
in the operator's dashboards. Configure conservative application budgets using the
[hosted Alpha.2 policy](HOSTED_ALPHA.md); application estimates are not provider meters.
The Function rejects oversized bodies before forwarding. Origin admission limits
protect database/CPU work, but requests reaching the tunnel can already consume ngrok
quota even when refused. Keep its URL private. A provider hard quota can interrupt
Cloud and network rollback until reset; installed apps retain verified offline state.

## Configuration

- **Pages project:**
  - Root directory `v2/web`, build `npm ci --ignore-scripts && npm run build`, output `dist`.
  - Leave `VITE_DOOTAH_CLOUD` unset and use `DOOTAH_WEB_BASE=/`.
  - Function variables: `DOOTAH_UPSTREAM` (the tunnel's `https://` origin) and
    `DOOTAH_EDGE_SECRET` (encrypted).
- **Host:**
  - Layer `compose.edge.yaml` after the Neon overlays and use `DOOTAH_CADDYFILE=Caddyfile.edge`.
  - `DOOTAH_EDGE_DIR` holds `secret` (mode 0600).
  - `DOOTAH_TUNNEL_DIR` holds the agent's `ngrok.yml` (owned by UID 1000, mode 0600). Its
    upstream is `http://proxy:80` and its local API is disabled (`web_addr: false`).
  - Keep both directories on tmpfs. Never put either value in Compose environment variables,
    images or logs.
