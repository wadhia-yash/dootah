> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# Dootah Cloud API v1

Implementation: `v2/cloud/server.mjs`. See [architecture](CLOUD_ARCHITECTURE.md),
[telemetry](TELEMETRY.md) and [rollouts](ROLLOUTS.md). No hosted origin is deployed.

## Authentication and responses

Use HTTPS. Browser users log in with `POST /v1/sessions` (`email`, `password`), with
Origin equal to the configured Cloud origin. The response sets the session cookie
and returns a `csrf` value. Browser mutations send `X-CSRF-Token`; organization
requests send `Dootah-Organization`. `DELETE /v1/sessions/current` revokes the session.

CLI/API requests use `Authorization: Bearer <token>`. Tokens carry organization,
optional app/environment restrictions and scopes internally; a supplied conflicting
organization header is rejected. Device enrollment and ingestion use separate
credential kinds and cannot access user/control routes.

Errors are `{"error":{"code":"...","message":"...","requestId":"..."}}`.
401 means invalid/expired/revoked credentials; 403 insufficient permission; 404
unavailable tenant/resource; 409 state/idempotency conflict; 413 body limit; 422 field
validation; 429 rate limit with Retry-After. Database/upstream bodies and stack traces
are not returned. Unknown routes return 404. The HTTP body limit is 128 KiB; events
have an additional 32 KiB limit. Object fields are allowlisted.

Resource lists return `items` and `nextCursor`: `limit` defaults to 25, maximum 100;
pass the cursor back as `after`. UUID ordering is stable; events use an ID/installation
pair and audit uses its sequence ID. Every page reapplies tenant/token filters.
App/environment/release filters are accepted only on applicable collections.
Organization and team lists currently cap at 100 rather than paginate.

## User/control routes

| Route | Request / permission |
| --- | --- |
| `GET/POST /v1/organizations` | User session; list own memberships / create with `name` |
| `GET/POST /v1/members` | Owner session; list / add provisioned `email`, `role` |
| `PATCH/DELETE /v1/members/{userId}` | Owner; change `role` / remove; last-Owner guard |
| `GET/POST /v1/apps` | `app:read` / Owner creates with `name` |
| `GET /v1/apps/{id}` | `app:read`, token scope enforced |
| `GET/POST /v1/environments` | `app:read` / Owner creates `appId`, `name`, `runtime`, `contract` |
| `GET /v1/environments/{id}` | Registered runtime/capability/function contract |
| `GET/POST /v1/releases` | `release:read` / `release:publish` |
| `GET /v1/releases/{id}` | Release, metrics, most recent operation |
| `POST /v1/releases/{id}/rollout` | `release:control`; `version`, integer `percentage` 0–100 |
| `POST /v1/releases/{id}/pause` | `release:control`; `version` |
| `POST /v1/releases/{id}/resume` | `release:control`; `version` |
| `POST /v1/releases/{id}/rollback` | `release:control`; `version`, optional bounded `reason` |
| `POST /v1/releases/{id}/kill` | Same authorization, terminal release stop |
| `GET /v1/operations/{id}` | `release:read`, including app/environment scope |
| `GET /v1/installations` | `telemetry:read` |
| `GET /v1/events` | `telemetry:read` |
| `GET /v1/audit` | `audit:read`, organization-wide scope required |
| `GET/POST /v1/tokens` | Owner session; token metadata / create scoped token |
| `DELETE /v1/tokens/{id}` | Owner session; revoke |
| `POST /v1/enrollment-tickets` | `release:publish`; `environmentId`; ten-minute single-use ticket |
| `POST /v1/enroll` | Enrollment bearer ticket; installation registration/credential rotation |
| `POST /v1/events` | Installation bearer credential; bounded event batch |

Token creation accepts unique `scopes`, `expiresDays` (1–90), optional `appId` and
`environmentId`. Environment restriction requires its owning app. Available scopes:
`app:read`, `release:read`, `release:publish`, `release:control`, `telemetry:read`,
`audit:read`. Effective role may reduce these permissions. The secret is returned
once; lists contain metadata only. Device credentials cannot publish or mint tokens.

## Releases and local publisher

`POST /v1/releases` accepts `environmentId`, `revision` (1–256 characters) and
`artifact` (Portable IR object, maximum 65,536 serialized UTF-8 bytes). It requires
`Idempotency-Key`. Cloud derives runtime/ABIs, artifact hash, registered contract
digest and creator. It returns HTTP 200 with a durable operation (`id`, `resource_id`,
`action`, `status`); this is acceptance of intent, not completion of publication.
Poll `/v1/operations/{id}`. Repeating identical intent returns the same operation;
conflicting reuse returns 409. Rollback and kill use the same idempotency convention.
New publication validation is limited to 30 requests/minute/organization.
Policy mutations require the current release `version` and return its increment.

Cloud's contract is `{capabilities, functions}`. `capabilities` is the unchanged
`PortableProgram.contract()` object; `functions` maps installed hook identities to
portable parameter-type arrays. The operator registers this from trusted retained
APK/import evidence. Full publisher contracts contain source and **must not be
uploaded**. Phase 9C's supported `dootah env create` / `dootah contract register`
commands derive this contract from retained build evidence through the existing
authenticated API, with one fixed runtime/contract per environment. See the
[customer guide](../../v2/publishing/ONBOARDING.md). The Samsung example registers the checked-out
business-rule hook used by its proof; it is not a multi-app contract coverage claim.

The existing `dootah publish /private/dootah-publish.json` path accepts this extra
local configuration alongside its retained APK/source/contract pins:

```json
{
  "cloud": {
    "url": "https://cloud.example.com",
    "organizationId": "<Dootah organization UUID>",
    "environmentId": "<Dootah environment UUID>",
    "tokenEnvironment": "DOOTAH_CLOUD_TOKEN"
  },
  "sourceRevision": "<commit or CI revision>"
}
```

Cloud app ID, environment name and runtime must agree with the imported APK contract.
The CLI runs local analysis first, sends IR only and saves the Cloud operation/release
receipt. It does not automatically enable rollout. An optional `cloud.idempotencyKey`
allows retrying the same generated publication. Source edits/unsupported Kotlin still
cannot affect normal Android builds. The old direct self-hosted publisher remains
available for operators; the Cloud workflow does not expose its credentials.

## Local setup and deployment boundary

Requires Node 24, PostgreSQL 17 and JDK 21. From the repository:

```sh
npm ci --prefix v2/cloud
JAVA_HOME=/path/to/jdk21 v2/cloud/validator.sh
```

The PostgreSQL operator creates a database owned by a dedicated migration login,
a cluster role `dootah_cloud_runtime NOLOGIN`, and a separate runtime login granted
membership in that role. Configure a private environment file, mode 0600:

```text
DOOTAH_CLOUD_DATABASE_URL=<runtime PostgreSQL DSN>
DOOTAH_CLOUD_ORIGIN=https://cloud.example.com
DOOTAH_DELIVERY_BINDINGS=/private/delivery-bindings.json
DOOTAH_JAVA=/path/to/jdk21/bin/java
DOOTAH_VALIDATOR_CLASSPATH=/repo/v2/cloud/build/validator:/repo/v2/publishing/build/install/dootah-publishing/lib/*
PORT=3100
```

Run migrations with a **separate file containing the migration-owner DSN**, then
bootstrap users using a securely supplied `DOOTAH_INITIAL_PASSWORD` (14+ characters):

```sh
node --env-file=/private/migration.env v2/cloud/admin.mjs migrate
node --env-file=/private/migration.env v2/cloud/admin.mjs user owner@example.com
node --env-file=/private/cloud.env v2/cloud/server.mjs
```

The HTTP listener binds loopback for a TLS reverse proxy. Only for loopback testing,
set origin `http://127.0.0.1:3100` and `DOOTAH_ALLOW_LOCAL_HTTP=true`; secure-cookie
relaxation is restricted to that mode. Never expose xprem's publish routes publicly.
`/ready` checks database migration availability, not upstream delivery liveness.

Operator-only delivery bindings map a Cloud app UUID to `base` (private backend
origin), `publicOrigin` (Cloud gateway origin), upstream `appId`, per-app `apiKey`
and public `certificate`. Use the existing xprem provisioning command to allocate
an isolated app/key when onboarding another Cloud app. Protect this file and the
existing xprem master/signing state; it is never frontend/SDK configuration.
The binding file cannot assign one upstream app to multiple Cloud apps.

The verified development deployment uses Cloud port 3100, private xprem port 3102,
PostgreSQL port 54329, and `~/.dootah-v2/cloud-state/{cloud.env,migration.env,bindings.json}`.
It reuses the existing xprem signing/storage service. No EAS endpoint/account is used.

Tests are in `v2/cloud/test`. Integration tests require operator-provisioned isolated
tenants and a supported IR fixture through `DOOTAH_TEST_TENANTS`,
`DOOTAH_TEST_ARTIFACT`, `DOOTAH_TEST_PASSWORD_FILE`, and the test DB environment.
They mutate those fixtures; never target production. Missing fixture settings cause
integration tests to skip, which does **not** satisfy acceptance. Curated Phase 7
results record a run with all 13 tests enabled and no skips.
