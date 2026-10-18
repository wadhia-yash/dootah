# Neon PostgreSQL with the existing Cloud

Cloud keeps its `pg` driver, SQL migrations, scrypt passwords, database sessions,
organization roles, release worker and private xprem integration. Neon Auth, Data
API, Functions and other services are not used. `neon deploy` reconciles database
service configuration; it does not deploy Dootah's HTTP server or frontend.

## Connection and migration boundary

An optional local `neon.ts` (not committed) may declare PostgreSQL-only Neon configuration. `.neon`, `.env*` and
`dootah-private/` are ignored; never put their contents into frontend variables,
commits or logs. Neon agent skills and MCP configuration belong in user-level
agent configuration, outside the repository.

Cloud resolves credentials in this order:

1. `DOOTAH_CLOUD_DATABASE_URL` (or its private `_FILE`).
2. `DATABASE_URL_UNPOOLED` (or its private `_FILE`).
3. `DATABASE_URL` (or its private `_FILE`).

Explicit Dootah credentials override Neon exports so an owner URL cannot override
a configured runtime login. Conflicting value/file inputs fail. Use direct Neon
TLS connections with `sslmode=verify-full`: Cloud sends `search_path` and
`statement_timeout` startup options that Neon's transaction pooler rejects.
Cloud retains its bounded pools (10 application connections, 2 limiter connections).
Do not disable TLS verification or remove timeout/search-path protections.

The connection URL pulled by Neon normally belongs to the owner. It is for explicit
migrations, not production HTTP. Before migration, inspect existing schemas, migration
versions and role privileges. Test changes on a temporary Neon branch first. Never
reset/drop an existing database. For a fresh database, create `dootah_cloud_runtime`
NOLOGIN and a separate `cloud_runtime` login with NOSUPERUSER, NOCREATEDB,
NOCREATEROLE, NOREPLICATION and NOBYPASSRLS. Grant the NOLOGIN role to that login.
Generate its password privately with at least 32 random bytes. Do not grant it the
Neon owner role or schema/database CREATE privileges.

Apply migrations with a private owner URL file:

```sh
DOOTAH_CLOUD_DATABASE_URL_FILE=/private/cloud-owner-url node v2/cloud/admin.mjs migrate
```

Migration 008 grants user INSERT for registration; runtime cannot UPDATE/DELETE
users. Migrations remain explicit, transactional and advisory-locked. HTTP startup
requires version 9 and refuses owner/overprivileged credentials. Run migration twice
to verify idempotency. Store runtime and owner URLs separately with mode 0600.

## Public backend and frontend deployment

Use the existing single-host deployment in [DEPLOYMENT.md](DEPLOYMENT.md). A host,
domain, DNS and operator secret/backup custody are still required. No paid hosting
is provisioned by this integration.

1. Build the existing images with `bash v2/deploy/build.sh /path/to/pinned-xprem`.
   The pinned upstream source and license audit requirements are unchanged.
2. Build the website with `npm --prefix v2/web ci --ignore-scripts` and
   `npm --prefix v2/web run build`. Leave `VITE_DOOTAH_CLOUD` unset and set
   `DOOTAH_WEB_BASE=/` (or leave it unset). The GitHub Pages workflow deliberately
   remains a demo-only deployment.
3. Generate fresh host secrets using the canonical deployment guide. Bootstrap the
   private xprem database before replacing Cloud's generated owner/runtime URL files
   with the separately prepared Neon URLs. Do not run local bootstrap afterward:
   it assumes local Cloud credentials. xprem keeps its existing private PostgreSQL,
   signing identities and persistent asset volume.
4. Set the deployment env file's HTTPS `DOOTAH_CLOUD_ORIGIN`, `DOOTAH_SITE`,
   `DOOTAH_SECRETS_DIR`, `DOOTAH_CADDYFILE` and an absolute `DOOTAH_WEB_DIST` path.
   `DOOTAH_WEB_DIST` must contain the built `dist/`. Use the same hostname for
   frontend and API; cross-site credential sharing is not supported.
5. Run explicit migrations, then start the composed services:

```sh
docker compose --env-file /private/deploy.env \
  -f v2/deploy/compose.yaml -f v2/deploy/compose.neon.yaml \
  -f v2/deploy/compose.web.yaml --profile operator run --rm cloud-migrate

docker compose --env-file /private/deploy.env \
  -f v2/deploy/compose.yaml -f v2/deploy/compose.neon.yaml \
  -f v2/deploy/compose.web.yaml up -d postgres xprem cloud proxy
```

Use the same three Compose files for subsequent operations. Only Cloud and Cloud
operator jobs gain a Neon egress network. xprem/database host ports remain private;
only Caddy publishes 80/443. The web override serves `/`, `/console`, fonts and JS
from the static build and proxies API/delivery paths to Cloud. There is no database
credential in the frontend. Confirm `/ready`, registration, login, refresh, sign-out
and role denial through public HTTPS before inviting users.

Provision each app's xprem delivery binding using the existing operator procedure
with this Compose configuration. Pass both overrides to the operator helper:

```sh
python3 v2/deploy/ops.py --env /private/deploy.env \
  --compose-file v2/deploy/compose.neon.yaml \
  --compose-file v2/deploy/compose.web.yaml \
  binding-put CLOUD_APP_UUID /private/xprem-provision.json
```

Use the same overrides for `grant-xprem` and other applicable operator jobs. Register the installed contract with the existing
CLI before publishing. Registration alone does not provision signing identities.
Keep backups of both databases plus xprem assets and matching keys; the existing
local-only backup script is not a Neon backup implementation. Do not claim a public
production deployment until Neon backup/restore and host operations are validated.

See [hosted Alpha.2](HOSTED_ALPHA.md) for opt-in invitations, quotas and migration 009.

## Account and release behavior

`POST /v1/users` requires exact Origin, an email, and a 14–256 character password.
It uses the existing scrypt implementation, forbids extra fields and rate-limits
registration per client. Login/logout retain the existing HttpOnly, Secure,
SameSite=Strict eight-hour sessions. `GET /v1/sessions/current` restores CSRF only
for an authenticated session; it does not extend expiry. CSRF stays in memory.
Registration does not grant access to an existing organization. Its creator becomes
Owner only when creating a new organization. Developer/Viewer permissions remain
server-enforced. Email verification and password recovery are not implemented.

Release publication, rollout, pause/resume and rollback retain existing version,
idempotency, contract, signing and role checks. Rollback returns to the embedded
native baseline; it does not select an older OTA release.

## Verification

`DOOTAH_TEST_ACCOUNTS=true node --test v2/cloud/test/accounts.test.mjs` creates real
users and an organization/app in the configured database. Run it only on a disposable
migrated branch with restricted runtime credentials and explicit development origin,
or inside the disposable production acceptance harness. It tests negative origin,
password/input validation, duplicates, hashed storage, denied user mutation, session
restoration/revocation, persistence, role denial and registration throttling.
The full deployment harness now includes this account test. Never point generic
fixture tests at production data.

See the task report for actual results. A database connection, frontend build or
signed transport test is not physical-device OTA proof or production readiness.

## Integration verification — 2026-10-14

- Neon CLI 8.4.4 authenticated and linked the operator's Neon organization and
  project (`production` branch, free plan).
- Reviewed `neon config plan`; `neon deploy` completed with no service changes.
- Production was inspected as empty before migration. Migrations 1–8 applied;
  repeat migration succeeded. Separate runtime credentials passed schema and
  privilege readiness. Production was not populated with test accounts.
- On temporary branch `dootah-integration-20261014`: 23 Cloud tests passed, 0 failed,
  0 skipped. Real registration/login, organization/app storage, role denials,
  logout/re-login, signed publish, rollout, pause/resume, kill and rollback passed.
  Cloud restart preserved app data and cryptographically verified rollback delivery.
  xprem ran in a disposable local stack with its own private PostgreSQL and keys.
  This verifies Cloud against Neon, not a publicly hosted deployment.
- Cloud Docker image `dootah-cloud:neon-integration` built successfully from current
  Cloud and validator sources. xprem reused the existing pinned composition image.
- Frontend: 14 Vitest tests passed; TypeScript/Vite production build passed.
  Configuration/proxy/operator checks: 15 passed. Earlier focused Cloud unit,
  client, deployment and source-audit invocation: 25 passed. Counts overlap.
- Documentation unit tests: 5 passed. Documentation validation still reports missing
  historical banners in the pre-existing untracked `DOOTAH_TIER1_FEASIBILITY_GATE.md`
  and `DOOTAH_V1_TO_V2_FEATURE_PARITY_AUDIT.md`; these were not changed.
- SDK/server/security isolated gates were attempted and refused the uncommitted
  source tree. No hosted CI result is claimed. No physical-device OTA test was rerun.
- OAuth MCP entries and `neon`/`neon-postgres` skills installed for Codex and Claude
  Code. Agent MCP authorization is separate from CLI login and occurs on first use.
- Temporary branch expires 2026-10-15 at 12:00 UTC (17:30 IST). Disposable Docker
  containers/volumes from verification were removed. Existing device stacks were
  left running. Private local configuration/evidence remains ignored.

Remaining public deployment work: choose the host/domain, deploy reviewed images and
same-origin web build, provision real application delivery bindings, and verify public
TLS plus Neon/xprem backup and restore. Account email verification/password recovery,
public operational readiness and hosted CI remain outside the completed checks.
