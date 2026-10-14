# Cloud API

The self-hosted Cloud API uses HTTPS and organization-scoped authorization.
See [deployment](../../v2/deploy/DEPLOYMENT.md) and
[CLI onboarding](../../v2/publishing/ONBOARDING.md) for setup.

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
uploaded**. The supported `dootah env create` / `dootah contract register`
commands derive this contract from retained build evidence through the existing
authenticated API, with one fixed runtime/contract per environment. See the
[customer guide](../../v2/publishing/ONBOARDING.md).

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
