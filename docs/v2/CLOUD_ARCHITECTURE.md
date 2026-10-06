> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# Dootah Cloud — Phase 7

Phase 7 adds a local, production-oriented control-plane MVP on top of the accepted
Phase 6 engine. Implementation: `v2/cloud`. PostgreSQL migrations: `001`–`006`.
Curated acceptance is in [Phase 7 evidence](evidence/phase7-20260928/acceptance.json).
This is not a hosted production deployment or a claim of enterprise readiness.

## Boundary

```text
Dashboard / local Kotlin publisher / CI
                 |
          Dootah Cloud /v1
          |              |
 Dootah PostgreSQL   private delivery adapter
                          |
                    xprem MIT services
                          |
Android <-- Dootah delivery gateway <-- signed manifests/assets/directives
```

Cloud owns users, organizations, membership, apps, environments, credentials,
releases, policy, installations, telemetry, metrics and audit. It exposes Dootah
UUIDs, not xprem publisher credentials or numeric update identifiers. The native
transport still uses signed update UUIDs; the event adapter maps these to Cloud
releases. The customer API does not require xprem's branch/upload structures.

xprem retains artifact storage, upload integrity, manifest signing and signed
rollback. The only addition to its composition is a private, authenticated,
idempotent channel-provisioning route. Each Cloud release and rollback operation
gets an isolated internal channel. A staged upload cannot become the public head
through upstream latest-update selection. The gateway chooses channels from durable
Cloud policy, never a caller-provided upstream branch. Asset requests require a
matching app/environment/artifact offer grant. Signing bytes and asset hashes are
preserved; the gateway does not manufacture an unsigned control directive.

The service reuses the existing PostgreSQL instance with a separate `dootah_cloud`
database and schema. Cloud's login is a non-owner role without superuser, role/DB
creation or schema-creation permissions. It has no xprem key-store privileges.
The migration owner is separate; schema changes run only through `admin.mjs migrate`.
Versioned SQL includes tenant composite foreign keys, indexes and append-only audit
permissions. No schema mutation occurs in the HTTP process.

## Authorization

User → organization membership → app → environment → releases/installations.
Organization-owned tables carry `organization_id`. All customer-resource queries,
aggregations and mutations constrain it and the token's app/environment scope.
Composite foreign keys reject cross-tenant parent, release, environment, credential
and operation associations. Identity-level login/session lookups resolve the actor
before tenant access. Internal scheduling/delivery resolves a trusted tenant mapping
before operating on its records. SQL row-level security is **not** enabled: explicit
query scoping plus constraints and tested authorization are the MVP boundary.

Owner manages members, apps, environments and tokens. Developer can read, publish
and control releases. Viewer can read but cannot mutate releases. Token scopes are
intersected with current membership/role, so membership removal or downgrade affects
existing tokens on their next request. The last Owner cannot be removed/demoted.

Operator-provisioned users have salted scrypt password hashes. Browser authentication
uses random opaque sessions with only SHA-256 digests stored; sessions expire after
eight hours. Cookies are HttpOnly/SameSite=Strict and Secure under HTTPS. Mutations
check exact Origin and a session-bound CSRF value. Login has IP/account rate limits,
generic rejection and a dummy password comparison for unknown users. No public
signup, recovery email, SSO or billing is implemented.

CLI tokens are random 256-bit secrets, returned once, hashed, scoped to organization
and optionally app/environment, with explicit permission lists and 1–90 day expiry.
Revocation is immediate on subsequent requests. They cannot administer members or
mint credentials. Private signing material stays in xprem. Internal publisher keys
are per app and used only behind the private adapter; customers never receive them.
Operator bindings reject reuse of one upstream app across Cloud apps.

## Publication and operations

The existing separate Kotlin analyzer and retained-APK contract checks remain local.
The Cloud CLI path sends Portable IR, environment and bounded source revision only;
it does not upload source, APKs or full contracts containing source. Cloud validates
IR using the **same Java `PortableProgram`** as the SDK against an operator-registered
capability/function contract. Runtime ABI 2 and Logic ABI 1 are required.

A release stores tenant/app/environment, runtime, ABIs, contract digest, source
revision, artifact SHA-256, creator/time, status, rollout and private delivery mapping.
Exact artifact bytes are retained temporarily for durable publish intent and cleared
once xprem publication succeeds. The first host integration attempt exposed JSONB
reserialization changing bytes; it failed closed before distribution. The corrected
path hashes and uploads the same retained bytes. That failed attempt is not acceptance.

Durable operations contain organization, actor, resource, idempotency key, request
digest, action, status and private signed-delivery receipt. Conflicting key reuse is
rejected. Mutations serialize per environment and use release versions. The worker
locks operations, inspects their isolated channel and verifies signatures/hashes
before completing the local transaction. After interrupted upstream success it can
reconcile the existing signed result instead of publishing a duplicate. Ambiguous
errors remain pending and retry; the UI exposes that state, not false success.

Statuses are draft, ready, rolling_out, paused, active, rolled_back, killed and failed.
Ready does not distribute; rollout enables a ready release. Active means 100%
distribution policy, not universal adoption. Replacing an environment head pauses
its former distributing release. Rollback/kill is completed only after the signed
native-restoration directive is durable. See [rollouts](ROLLOUTS.md).

## Native telemetry baseline and authorization

The Phase 6 APK acknowledged frame health only locally. It could not satisfy the
requested genuine Cloud health gate without native work. After this was explained,
the user explicitly authorized **a minimal native telemetry SDK update and a new
acceptance baseline, preserving ABI 2 / Logic ABI 1**. SDK/plugin `0.7.0-local` adds
an out-of-band native telemetry sender and uses the updater's existing state-change
subscription. No Expo patch, grammar, capability, dispatch visitor, native renderer
or sandbox adapter changed. Telemetry is unreachable from portable code.

The accepted replacement was installed once at `2026-09-28 20:45:44 IST`, APK SHA-256
`4005bafe3578c2638c25a6de4823f1e3e545c6f7a79d35c0c6c575c634da7eca`.
No further installation/data clear occurred during its accepted sequence. The
previous Phase 6 installed hash was verified before replacement. The final pulled
APK matches the replacement artifact, and every captured stage has the same install
time. The original same-APK requirement is therefore applied **after the explicitly
authorized new baseline**, not misreported as retaining the Phase 6 binary.

## Audit and dashboard

Audit records organization, actor, action, resource, server time and bounded metadata.
App creation, membership/role changes, token create/revoke, publication request/result,
rollout, pause/resume and rollback/kill request/result are recorded. The runtime DB
role cannot update, delete or truncate audit; an additional trigger rejects mutation.
This is application-immutable audit, not protection from a database administrator.

The same-origin dashboard provides Apps/overview, Releases/detail, Installations,
Telemetry/health, Team, API tokens and Audit. It uses the same API authorization,
text-only DOM rendering, CSP and no third-party scripts. Release detail exposes
runtime, environment, policy, adoption/health counts, status, time and pending operation,
with Pause/Resume/Rollback/Kill actions. Basic layout and JSON detail views are
intentional MVP choices. Owner operations remain available through the API when the
minimal UI lacks a dedicated form.

## Evidence and limits

Two organizations/apps exercise denial of foreign reads, publication, controls,
operations and telemetry, including internal IDs and same-organization app scopes.
Tests also cover CSRF, token expiry/revocation, role changes, last Owner, bounded
payloads, event deduplication, SQL constraints/permissions, deterministic cohorts,
real signed publishing and terminal kill. Physical acceptance covers two Kotlin
publications, real native events, adoption, pause/resume, cached operation without
Cloud, signed rollback and offline restored native behavior. PostgreSQL/xprem/Cloud
restart retained scoped rows, policies, mappings, audit and telemetry. A further
source publication after restart remains ready at 0%, without changing the phone.

Limits: one registered runtime/contract per environment; operator-managed upstream
bindings/users/enrollment; repository-local CLI and Maven artifacts; no autoscaling,
crash telemetry, automatic rollback, email, billing or public deployment. Device
reports are authenticated self-reports, not attestation. Telemetry is best effort,
with bounded disk queue and 30-day installation credentials; re-enrollment rotates
credentials. Clock skew, a stale/invalid queued event or expired credentials can
prevent batch ingestion and requires operational attention. Retention is presently
operator-managed; metrics explicitly use a 30-day window. No claim of infinite
queue retention, instant revocation or crash-free analytics is made.

Production deployment must terminate HTTPS, impose ingress connection/IP limits,
keep private backend ports inaccessible, protect/backup DB and key-store secrets,
and supervise the processes. The tested setup is loopback with ADB reverse, not
internet exposure. Pending operations need operator monitoring; alerting and long-term telemetry retention jobs are not implemented. Retries use
bounded exponential backoff so a failing app does not monopolize the due-operation queue.

## Exact Phase 8 starting point

Start only after the Phase 7 commit from this Cloud MVP and SDK `0.7.0-local`, frozen
Runtime ABI 2 / Logic ABI 1 and the accepted Samsung baseline above. The phone is
restored to native `Discount: 10`; consumer source is restored too. Begin by defining
the **frozen multi-app acceptance matrix** for the existing compiler/publisher,
Cloud, signed delivery, sandbox, telemetry and rollback surfaces. Do not expand
capabilities/grammar or billing as an implicit continuation. Phase 8 has not begun.
Local operator state is outside Git at `~/.dootah-v2/cloud-state`; raw proof files
and retained acceptance contract/APK are under `/tmp/dootah-v2-phase7`.
