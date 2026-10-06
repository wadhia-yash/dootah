> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# Phase 9D — production deployment design and implementation

2026-10-04. **PASS, locally.** Clean starting branch `pivot/dootah-v2`, HEAD
`1f723c0b17115556ae43d653b3773dd1fb35524b`. Docker/Compose were available.
`DOOTAH_CURRENT.md` was absent; the explicit Phase 9D task defined this milestone.

Implementation commits: `d5288ea` (Cloud production validation/readiness), `9ccf050`
(private xprem migration/provisioning), `8d7aaff` (containers, Compose, operator tools
and acceptance). This evidence/documentation commit follows them. No Phase 9E–9H
work, external deployment, public image push or Maven/JFrog publication occurred.

## Design reviewed before implementation

The working log recorded the design after inspecting Cloud/xprem code, local scripts,
migrations/grants, signing/storage/bindings, health/configuration and 9C expectations:

- Single Linux VM, Caddy HTTPS ingress, Cloud application gateway, private xprem and
  PostgreSQL 17. Only Caddy publishes ports. Separate internal ingress/backend networks
  prevent the proxy from directly joining the database/signing network.
- Explicit production mode, required HTTPS public origin, packaged JDK 21 validator,
  file-injected secrets, separate owner/runtime credentials and no HTTP-startup migrations.
- OS-authenticated operator provisioning and a validated private bindings file; no
  customer-editable raw binding API or customer DB writes. Existing Cloud APIs remain
  authoritative for customer state and authentication.
- Independent liveness; readiness checks critical configuration, schema, runtime role
  and validator. Individual xprem delivery outages do not make Cloud globally unready.
- Maintenance-window DB/storage/key/config backups; restore matching state into fresh
  volumes. Upgrades explicitly migrate and restart, with schema-dependent rollback.

This required no runtime ABI, portable grammar, capability, renderer, sandbox,
signing/trust or native-fallback change. All inspected boundaries were preserved.

## Delivered and verified

The [deployment guide](../../v2/deploy/DEPLOYMENT.md) contains prerequisites, resource
sizing, complete external user actions, exact commands, secrets, TLS, provisioning,
operations, backups/restores/upgrades and troubleshooting. The root public README was
not rewritten. Sanitized templates live alongside [Compose](../../v2/deploy/compose.yaml).

Cloud's multi-stage image contains Node 24 and the Java 21 validator/JRE, with no Gradle
or developer build-folder dependency. The xprem image contains the audited composition
of the pinned `b46e13569f5734a66ed903f75f51b87b78e80c1b` upstream. Both use explicit
allowlisted build inputs, pinned base manifests, health checks and non-root HTTP
processes. Entrypoints briefly read mode-0400/0600 host secrets as root into tmpfs, then
drop to UID 10001. Runtime services never receive migration-owner or bootstrap secrets.
No commercial EE or mixed-license router enters the xprem deployment context/graph.

Cloud production startup rejects missing/malformed DB configuration, non-HTTPS origin,
local HTTP bypass, invalid bindings, missing Java/validator and overprivileged/unmigrated
runtime DB access. Listen host is configurable; development retains explicit loopback
HTTP. `/live` and `/ready` have different semantics. Successful global readiness checks
are coalesced/cached for five seconds. HTTP runtime with the migration-owner credential
was rejected; actual runtime roles were denied schema creation in both databases.

Production xprem migrations and storage migrations are explicit one-shot commands.
Configurable app name/channel replaces Phase 2B provisioning naming. Upstream uses the
app name for certificate CN without a signing change. A private authenticated binding
check confirms the provisioned identity/certificate. Operator replacement validates
Cloud app existence, private/public origins, certificate, API key and app exclusivity;
invalid credentials leave the previous file intact. API-key rotation preserves signing
identity, verifies signed operations and then revokes the old key. Binding inspection
returns redacted metadata. Only the existing operator boundary can create native
signing identities, users and database roles.

Production logs expose safe lifecycle events; raw upstream logger content is suppressed.
The final harness checked DB passwords, master/upload keys, old/new xprem API keys,
Cloud API tokens and session cookies against service logs. No secret matched. Backup
artifacts/credentials and raw logs remain in private temporary directories outside Git.

## Acceptance and counts

[The repository harness](../../v2/deploy/acceptance.py) ran the complete production-mode
Compose stack locally on Linux/ARM64 containers. It used Caddy `tls internal`, generated
local CA trust in the test client, and loopback-only ephemeral host ports. No TLS bypass,
public domain, public ACME request, user cloud account or external infrastructure was
used. No phone rerun or new device OTA claim was needed: runtime binaries are unchanged.

It proved fresh Postgres health, explicit/idempotent bootstrap/migrations, xprem health,
Cloud readiness, HTTPS proxy routing, no published Cloud/xprem/Postgres host ports,
non-root HTTP processes, operator user/app provisioning, authenticated customer org/app/
environment creation and contract registration, signed publication and rollback,
invalid-binding refusal, credential rotation, schema privilege denial, startup negatives,
all Cloud tests, restart persistence and log secrecy. With PostgreSQL stopped, liveness
remained 200 and readiness returned 503; with xprem stopped, Cloud remained ready.

The backup/restore smoke test stopped writers, dumped both DBs, archived storage and
copied keys/bindings/operator config. It restored into **another fresh Compose project
and new volumes**, using the backed-up secrets rather than newly generated ones.
All restored storage-file hashes matched. Retained apps/tokens remained usable and the
restored rollback signature verified cryptographically against the original certificate.
Temporary acceptance containers, networks and volumes were removed. The production
Caddyfile also passed `caddy validate` with networking disabled and a reserved example
hostname; no public certificate was requested.

| Suite | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: |
| Production config/binding validation | 10 | 0 | 0 |
| Cloud API/auth/DB/signed backend | 13 | 0 | 0 |
| Complete production Compose/restore acceptance | 1 | 0 | 0 |
| CLI/portable/server-audit Node tests | 11 | 0 | 0 |
| Publisher JVM | 22 | 0 | 0 |
| Gradle plugin | 9 | 0 | 0 |
| SDK runtime | 7 | 0 | 0 |
| Native health | 3 | 0 | 0 |
| Real Compose fixtures | 4 | 0 | 0 |
| Packaging regressions | 13 | 0 | 0 |
| **Dootah total** | **93** | **0** | **0** |
| Pinned upstream xprem tests/subtests | 345 | 0 | 4 explicit Azure exclusions |

The four upstream exclusions are `TestAzuriteUploadListGet`,
`TestAzuriteSASUploadRequiresBlockBlobHeader`, `TestAzuriteCreateFrom`, and
`TestAzuriteDeleteUpdate`. Azure storage is not wired into this local-storage deployment.
Build tooling reports these by name and rejects unexpected skips; they are not counted
as passing tests. The graph audit verifies **341 byte-identical upstream files, 835
packages, 4,039 source entries, zero EE/router dependencies**.

V2's real Compose gate passed Debug/D8 and Release/R8 with 13 and 11 hooks. The historical
V1 `dootahRealComposeCheck` does not apply; compiler/rendering behavior was unchanged.
Normal Gradle conditional/NO-SOURCE statuses are not skipped suites. Runtime/native
health suites were explicitly rerun. The original 9B staged artifacts were not rebuilt
or published by this task.

Early development runs exposed harness-only private-file/copy handling and the DB-test
fixture detector's lack of `_FILE` recognition. Those were fixed; incomplete runs are
not acceptance. [Sanitized receipt](evidence/phase9d-20261004/acceptance.json),
[Cloud TAP](evidence/phase9d-20261004/cloud-tests.tap) and
[Node TAP](evidence/phase9d-20261004/node-tests.tap) record the final successful evidence,
input digests, image identities and counts. No raw credentials/logs/dumps are committed.

## Final contract and alpha bounds

Runtime ABI **2**, Logic ABI **1**, public SDK **0.1.0-alpha.1**, development SDK
**0.7.5-local**: unchanged. Runtime behavior changed: **NO**. Public xprem/Postgres ports:
**NO**. Secrets committed: **NO**. External infrastructure/deployment: **NO**.

Cloud container, xprem container, Postgres topology, reverse proxy, HTTPS enforcement,
listen configuration, startup validation, packaged validator, secret handling,
migration/runtime separation, delivery binding workflow, provisioning, live/readiness,
redacted logging, backup tooling, restore smoke, upgrade procedure and production-like
Compose acceptance: **PASS**.

Known limits: single VM/no HA; maintenance downtime for backup/upgrade/binding changes;
operator-owned files and users; local storage only; no automatic key re-encryption,
installed signing-key rotation, PITR, backup scheduling/off-host transfer, retention jobs
or alerting. Cloud retains an aggregate proxy-peer login limit. This is not an Internet
load/vulnerability audit. Ubuntu 24.04 is the target host; local acceptance was Docker
Desktop Linux/ARM64, not an external Ubuntu/AMD64 qualification. Resource sizes are
planning estimates. Production DNS/firewall/TLS and server configuration remain user
operations. No public certificate has been requested or verified by this work.

Phase 9E prerequisites: the user must separately define/authorize 9E and supply any
needed hosting, DNS, secret custody/backup destination and external publication/account
decisions. Exact external actions are in **USER ACTION REQUIRED — DEPLOYMENT** in the
deployment guide. None of them was performed here. Do not send secret values back.

**Phase 9D complete: YES. Phase 9E may begin when separately requested: YES.**
Stop at Phase 9D. Phase 9E is not started.
