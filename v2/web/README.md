# Dootah website and release console

React + TypeScript + Vite, Tailwind CSS and shadcn/ui (Radix). This is a local-first
frontend for the existing Dootah Cloud APIs. It changes no SDK, ABI, Cloud endpoint,
authorization rule or delivery behavior. The original Cloud dashboard remains intact.

The landing page, sign-in and console share a neutral dark theme, white actions and
blue accents. The landing composition follows the Expo reference with an original
Dootah monogram, radial hero illustration, interactive workflow and native product
visuals. Below 700px, the landing illustration stacks and navigation collapses; below
640px, release table rows become labeled cards.
Between 620px and 900px of list width, release rows also become cards, so the action
column is never clipped. Controls use touch-sized targets, form fields avoid
small-text mobile zoom, and dialogs scroll within the visible viewport.
Reduced-motion preferences disable animation and transitions.

## Local preview

Use Node 24 from the repository contributor environment:

```sh
cd v2/web
npm ci
npm run dev -- --port 5173 --strictPort
```

- Landing page: `http://127.0.0.1:5173/`
- Interactive sample console: `http://127.0.0.1:5173/console?demo=1`
- Cloud sign-in: `http://127.0.0.1:5173/console`

The demo uses in-memory sample data only. It makes no Cloud requests, resets on
reload, and never sends updates to devices. Sample telemetry is cleared when an
action changes the sample release; no new device reports are invented. The workflow and native
app illustrations are also browser-local, not OTA evidence.

The landing page describes experimental, source-only alpha.2 and links to the
current quickstart and support matrix. It makes no managed Cloud, public Maven,
general Kotlin support or crash-free health claims.

## Connect an existing local development Cloud

The development server proxies `/v1/*` to `http://127.0.0.1:3100` by default. Override
that target with `DOOTAH_CLOUD_URL` when starting Vite. Only use this with a local,
disposable development Cloud; do not point a test session at production.

Cloud must be separately running with its existing database, migrations and operator
provisioning. Follow [self-hosting](../../docs/self-hosting/README.md). For this local
preview, the Cloud process must use `DOOTAH_MODE=development`,
`DOOTAH_ALLOW_LOCAL_HTTP=true` and `DOOTAH_CLOUD_ORIGIN=http://127.0.0.1:5173`.
Keep the actual API listening on `127.0.0.1:3100` (Cloud `PORT=3100`). The browser's
Origin remains the preview origin through the proxy. CSRF/origin validation stays
enabled. Do not weaken those checks or rewrite the Origin header to make login work.

Sign in with an existing operator-provisioned account. Session cookies remain
HttpOnly; CSRF tokens stay in memory and reloading requires signing in again. The
frontend accepts no bearer-token input and writes no credentials to browser storage.
Viewer accounts see read-only controls. Server authorization remains authoritative.

The console reuses release list/detail, organization, app, environment and session
APIs. It supports search, filters, pagination retrieval, rollout percentage, pause,
resume, rollback and kill. It includes optimistic versions, confirmation, rollback
reasons, duplicate-click suppression, exact-request retry after ambiguous failure,
and polling while a Cloud operation is pending. A stale version requires reviewing
the refreshed release and confirming again. Rollback/kill restore the embedded native
baseline for the environment/runtime; they do not select an older OTA release and
are not instantaneous for offline or running devices.

This preview focuses on release management. Existing app/team/token administration
continues through the original dashboard and CLI; it is not replaced by this app.

## Verify and build

```sh
npm test
npm run build
npm run preview -- --port 4173 --strictPort
```

Tests cover the API boundary and release control policy, including ambiguous retries,
stale writes, denied actions, session context, pagination, pending/terminal states,
unknown telemetry denominators, demo state isolation and the browser's unbound
`fetch` requirement. Frontend tests do not prove native OTA delivery.

### Verification on 2026-10-13

- **Rendered review** in headless Chrome at 1440, 820, 390 and 320px: landing, demo
  console and sign-in. axe-core reported no violations on any page; there was no
  page-level horizontal scrolling. Mobile navigation, workflow tabs, the illustration
  toggle and demo controls were exercised.
- **Disposable Cloud**: a production-mode stack from `v2/deploy/compose.yaml`
  (alpha.2 Cloud image, xprem signing, PostgreSQL, local-TLS Caddy) served this build
  from the same origin. 31 browser checks passed: sign-in failure and success,
  HttpOnly/Secure/SameSite=Strict session, nothing stored in browser storage, sign-in
  required again after reload, sign-out revoking the server session, Owner/Developer
  controls, Viewer read-only UI with server 403 on writes, org isolation, rejection
  of CSRF-less writes and cross-origin sign-in, rollout percentage changes, pause and
  resume, stale-version rejection, and rollback with a reason that completed as a
  signed operation with `signatureVerified: true`.
- The live check found and fixed one blocker: the default transport called `fetch`
  as a method, so every real sign-in failed with "Connection interrupted".

This is website and Cloud API evidence, not device OTA evidence.

`dist/` is ignored and contains the static production build. Fonts are bundled
locally. No third-party analytics, email collection or external font request is used.

## Hosting

**Public site.** `.github/workflows/website.yml` builds with `DOOTAH_WEB_BASE=/dootah/`
and `VITE_DOOTAH_CLOUD=none` and deploys to GitHub Pages. The static build includes
the landing page and the demo console. Its `/console/` page explains that no Cloud or
accounts are hosted there and links to self-hosting; it never sends credentials.

**Beside your own Cloud.** Build with the defaults (`npm run build`) and serve `dist/`
from the same HTTPS origin as Cloud, so session cookies and CSRF/origin checks work
unchanged. Set Cloud's `DOOTAH_CLOUD_ORIGIN` to that origin. The disposable
verification above used these Caddy routes in front of the existing proxy settings:

```text
handle /web-assets/* { root * /srv/web; file_server }
handle /console*     { root * /srv/web; rewrite * /index.html; file_server }
handle               { reverse_proxy cloud:3100 { ... existing settings ... } }
```

Everything else, including `/v1/*`, `/updates/*`, `/assets/*` and the original
dashboard at `/`, stays on Cloud. Keep all production security headers. Do not
expose xprem, database ports, bindings, keys or operator files. The shipped
`v2/deploy` Compose files do not mount the website; adding it is an operator choice.
