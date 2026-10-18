# Hosted Alpha.2 protection and rollout

This implementation is opt-in and must not be enabled in production before review
and approval. It adds no services or databases. Neon remains the production database;
local Docker PostgreSQL is used only for disposable automated tests. Cloud/xprem
signing, delivery bindings, installed trust anchors and SDK behavior are unchanged.

## Configuration

Use one reviewed private environment file with `compose.hosted.yaml`, layered after
the existing deployment overlays. Set `DOOTAH_HOSTED_POLICY_FILE` to that file.
The overlay passes policy to Cloud and the existing `cloud-user` operator container.
Do not put database credentials or invitation tokens in this policy file.

All numeric values are positive integers except monthly budgets, which default to
zero/unconfigured. Hosted **production** startup requires both budgets to be explicitly
positive. Choose conservative application budgets using the operator's actual provider
account dashboards, including other traffic; the code does not assume provider plan
limits. A restart is required for environment configuration changes. Database pause
controls take effect across processes without a restart.

| Environment variable | Default |
| --- | --- |
| `DOOTAH_HOSTED_ALPHA` | `false` (self-hosted behavior) |
| `DOOTAH_INVITE_ONLY` | `true` when hosted policy is enabled |
| `DOOTAH_HOSTED_ORGANIZATIONS` | 5 |
| `DOOTAH_HOSTED_APPS` | 1 per organization |
| `DOOTAH_HOSTED_ENVIRONMENTS` | 2 per app |
| `DOOTAH_HOSTED_PUBLISHES` | 10 per organization |
| `DOOTAH_HOSTED_WINDOW_DAYS` | 30 rolling days |
| `DOOTAH_HOSTED_INSTALLATIONS` | 10 active enrolled IDs per organization |
| `DOOTAH_HOSTED_ARTIFACT_BYTES` | 5242880 (5 MiB) |
| `DOOTAH_HOSTED_PENDING_INVITATIONS` | 10 globally |
| `DOOTAH_HOSTED_MONTHLY_REQUESTS` | 0; explicit reviewed budget required |
| `DOOTAH_HOSTED_MONTHLY_BYTES` | 0; explicit reviewed budget required |
| `DOOTAH_HOSTED_WARNING_PERCENT` | 70 |
| `DOOTAH_HOSTED_CRITICAL_PERCENT` | 85 |

Hosted size is an additional ceiling. The existing 128 KiB HTTP envelope and 64 KiB
Portable IR limit remain stricter. Pages rejects oversized declared or streamed
bodies before forwarding to ngrok; Cloud checks the envelope before JSON parsing
and artifact bytes before Java validation or persistence. No ABI/grammar bounds
are widened to accommodate a 5 MiB portable program.

Rates are fixed one-minute windows, with `Retry-After: 60` for burst rejection:

| Environment variable | Default / key |
| --- | --- |
| `DOOTAH_RATE_REGISTRATION` | 5 per IP |
| `DOOTAH_RATE_LOGIN` | 20 per IP |
| `DOOTAH_RATE_LOGIN_EMAIL` | 10 per normalized email |
| `DOOTAH_RATE_AUTHENTICATION` | 60 registration/login attempts globally per process |
| `DOOTAH_RATE_IP` | 300 other requests per IP |
| `DOOTAH_RATE_DELIVERY` | 600 manifest/asset requests per IP and per organization |
| `DOOTAH_RATE_USER` | 300 per user and credential |
| `DOOTAH_RATE_READ` | 120 authenticated reads per user |
| `DOOTAH_RATE_MUTATION` | 20 org/app/environment/token/ticket creations per user |
| `DOOTAH_RATE_PUBLISH` | 30 new publish attempts per organization |
| `DOOTAH_RATE_GLOBAL` | 1200 admitted requests per process |
| `DOOTAH_RATE_CONCURRENT` | 8 in-flight requests per process before DB acquisition |

The existing durable PostgreSQL limiter remains for auth/user/org operations.
Hosted ingress and delivery use bounded process-local maps so invalid credentials,
rotating addresses and manifest polling cannot create an unbounded connection queue
or a database limiter write per check. Local maps reset on restart and are not a
cross-host DDoS limit. Run the existing single Cloud process; if scaling out later,
review aggregate admission budgets. High-frequency delivery still performs indexed
reads; grants are inserted only for offers not already recorded. Forged origin IP
headers remain ignored: Pages overwrites forwarding identity, Caddy authenticates
the edge secret, and Cloud trusts only Caddy's configured socket address.

No password-reset or browser/device OAuth token-exchange routes exist in this Cloud.
The CLI uses the same `/v1/sessions` and Owner-only scoped token APIs, which are covered
by the above limits. Do not introduce another auth system for this release.

## Platform administrator workflow

Platform administration is the existing local operator CLI with the schema-owner
migration connection, **not** an organization Owner session/API token. It checks
PostgreSQL schema-owner membership. Runtime credentials cannot issue/revoke invites
or change pause flags; there are no HTTP administration endpoints. Keep the owner
URL in its existing private mode-0600 file; never give it to developers or Cloud HTTP.

After approval and migration, in a private terminal with the same hosted policy:

```sh
export DOOTAH_CLOUD_DATABASE_URL_FILE=/private/cloud-owner-url
dootah-admin() { node --env-file=/private/hosted-policy.env v2/cloud/admin.mjs "$@"; }
dootah-admin invite developer@example.com
dootah-admin invitations
dootah-admin revoke-invitation INVITATION_UUID
dootah-admin usage
dootah-admin pause-registrations
dootah-admin pause-publications
dootah-admin resume-registrations
dootah-admin resume-publications
```

For the container workflow, use the existing `cloud-user` operator service with the
same Compose overlays and override its command with `node admin.mjs <command> ...`.
Set the public origin in the policy file too. The hosted overlay disables Docker
logging for this interactive operator service. Do not tee/capture the invite command
into application logs or analytics. It displays the raw token exactly once in a
copyable `/console#invitation=...` link. Only its SHA-256 hash is stored. The fragment
is never sent to HTTP servers and the console removes it from the URL immediately,
keeping it in memory for registration. List/revoke output contains no tokens. If the
link is lost, revoke its ID and issue another. Distribute manually over a private
channel. No email provider is required, and no email is marked verified.

Pause is serialized with new admissions. Paused publications are filtered before the worker batch limit so they cannot starve
rollback. Work already started before a pause can
finish; the worker stops starting/reconciling new publish work while paused and keeps
pending reservations. Rollback work still runs. Resume reconciles ambiguous uploads
using the existing upstream receipt/idempotency implementation. Never free a pending
slot merely because an upstream timeout occurred: it may have durably accepted data.

## Accounting and visibility

`usage` shows organizations/app counts, successful publishes within the rolling
window, pending reservations, known artifact sizes, unknown historical sizes, monthly
requests/update checks and estimated response/request body bytes. It labels 70%/85%
thresholds and projects a monthly run rate from observed month-to-date counters.
`GET /v1/usage` exposes publish remaining/reset information to authenticated,
organization-wide `app:read` callers. CLI quota errors retain only allowlisted quota
metadata and retry timing; dashboard errors show the same information.

Publication capacity includes pending/running operations, so two requests cannot
oversubscribe the organization. Successful usage starts at the worker's committed,
verified delivery receipt; retries return the original operation. Rollback is excluded.
Historical successful operations lacking a completion timestamp are conservatively
counted from migration time. Historical artifact bytes already cleared by the worker
remain **unknown**, not zero-sized claims.

Traffic counters batch at most once a minute, by UTC calendar month. They are
estimates, not a billing ledger: a crash/ambiguous flush may lose a batch, pre-enable
traffic is missing, headers/TLS overhead and rejected edge/direct-tunnel traffic are
not included, and projections during a partial first month understate activity.
Compare with ngrok/Cloudflare dashboards before acting. Monitor `usage` manually;
there is no new monitoring infrastructure or background notification service.
Pause invitations/publications before provider limits. Provider exhaustion can still
interrupt all network OTA including signed rollback; offline last-known-good survives.

## Migration and approval checklist

1. Review migration `009.sql` on disposable PostgreSQL, then a disposable Neon branch
   of the deployment before rollout. Use the existing explicit `admin.mjs migrate`
   with the owner connection; rerun to verify idempotency. No production test traffic.
2. Back up existing Neon schemas and preserve all binding/signing/operator secrets
   using the established operator procedure. No new database or AWS resource is needed.
3. Migration adds invitation/control/usage tables, organization classification,
   release size and operation completion fields/indexes. It deletes no user, session,
   organization, release or signing data. Existing organizations are initially
   classified as pre-existing (`hosted_external=false`) so the original Owner's
   organization does not consume an invited slot. Review that classification: any
   already-external organizations must be marked by the platform operator before
   enabling the hosted cap. New organizations count toward the cap. Existing resources
   over a limit remain readable/manageable; further creation is blocked.
4. Review exact policy/budgets, verify runtime privileges and deployed migrations,
   then obtain production approval. Deploy the reviewed Cloud image and Pages build
   together, adding `compose.hosted.yaml` only after approval. No deployment is performed
   by this task. Keep the private edge/tunnel configuration and existing trust identity.
5. After an approved rollout, perform a small invite/login/usage smoke check. To recover
   availability keep hosted policy enabled and use explicit pause/resume controls;
   disabling the hosted flag also reopens ordinary registration. Do not drop migration
   tables or rotate the established Owner/signing identity as a rollback shortcut.

Bounded follow-ups: email verification/recovery; authenticated unique-device delivery
admission (enrollment quota does not cover legacy unauthenticated manifest IDs); and a
12-hour client scheduling default for future APKs. None silently changes installed
APKs, signed directives or offline behavior. See [public limits](../../docs/reference/hosted-alpha.md).

## Focused local checks

```sh
npm --prefix v2/cloud ci --ignore-scripts
# Build the unchanged Java validator using JDK 21 and the existing publisher dependency.
JAVA_HOME=/path/to/jdk21 bash v2/cloud/validator.sh
node v2/cloud/test/run-hosted.mjs
node --test v2/cloud/test/policy.test.mjs v2/cloud/test/client.test.mjs v2/deploy/edge.test.mjs
node --test v2/publishing/customer.test.mjs
npm --prefix v2/web test
npm --prefix v2/web run build
```

The hosted harness creates its own loopback PostgreSQL container, ignores inherited
production database URLs, applies migrations twice and removes the container afterward.
The server acceptance harness additionally verifies signed rollback with hosted quota
exhaustion and emergency pause, and checks that a paused worker retains reservations.
CI requires the hosted reports. These are server tests, not new Android OTA/device proof.
