# Hosted Alpha.2 access

Dootah Cloud offers free experimental access by invitation. A platform operator
manually shares an invitation link with a developer. The link is bound to one
email address, expires after seven days and can create one account. Existing users
continue signing in. There are no paid subscriptions, payments or billing features.

These are the initial hosted limits, enabled only by the hosted deployment policy.
They do not apply to self-hosted Cloud/xprem installations. Self-hosting remains
available under the [existing licensing](../../LICENSE) and
[xprem license boundary](../../v2/server/XPREM-LICENSE.md).

| Resource | Hosted limit |
| --- | --- |
| New external organizations | 5 total; existing organizations are retained |
| Applications | 1 per organization |
| Environments | 2 per application |
| Successful publications | 10 per organization per rolling 30 days |
| Active enrolled test installations | 10 per organization |
| Artifact ceiling | 5 MiB; current Portable IR still enforces its stricter 64 KiB limit |
| Future integration check interval target | 12 hours; installed clients keep their existing behavior |

A developer can create one organization. Additional members can join existing
organizations through their Owners. Only the platform operator can issue account
invitations; being an organization Owner does not grant this permission.

When a quota is reached, Cloud refuses further creation with `429 quota_exceeded`
and includes the resource, limit, remaining capacity and next rolling-window
capacity release when known. Existing resources remain readable and manageable.
Publication retries with the same idempotency key do not consume another slot.
Pending operations reserve capacity until their outcome is known. Rollback, kill,
pause/resume and rollout do not count as new publications. Oversized requests or
artifacts receive `413`. Short request bursts can receive `429 rate_limited` with
`Retry-After`; clients should back off. These errors never represent signed OTA
instructions.

The operator may pause new registrations/invitations and publications before shared
hosting budgets are exhausted. Login, existing metadata and signed rollback delivery
remain available subject to ordinary network availability and short rate limits.
Already downloaded signed updates, offline fallback and local last-known-good
behavior do not change. A provider hard quota can still interrupt network OTA,
including rollback, until service is restored.

An invitation does **not** verify email ownership. Email verification and password
recovery are not currently implemented. Use a unique strong password and treat the
invitation link as a secret. The operator must distribute it privately.

The installation limit counts unexpired, unrevoked enrollment credentials for
random Expo installation IDs. It permits credential renewal for an already-active
ID. Reinstalling can create a new ID. The legacy public manifest protocol does not
authenticate that ID and does not require enrollment: this is **not** reliable
unique-device enforcement or a hard cap on delivery recipients. Adding authenticated
delivery admission requires a separately reviewed client/protocol change; existing
APKs are not silently excluded. See the [operator rollout guide](../../v2/deploy/HOSTED_ALPHA.md).
