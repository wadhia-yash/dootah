# Telemetry and metrics

See [enrollment](../../v2/publishing/ONBOARDING.md#telemetry-enrollment)
for the backend ticket and native integration workflow.

## Identity and enrollment

Reuse the installed updater's `EASClientID`: a locally generated random UUID stored
in app-private SharedPreferences. It is non-secret, not a hardware identifier and
requires no EAS service. SDK event identity and rollout headers use the same UUID.
App data removal resets it; Android backup/restore policy can affect reinstall
identity, so universal uninstall uniqueness is not promised. The sample disables
backup. No IMEI, serial, advertising ID, location or hardware fingerprint is collected.

An authorized developer/CI principal mints a ten-minute single-use enrollment ticket
for its app/environment. A customer backend can provide that ticket to its native
integration; the sample uses an explicit native enrollment dialog for the operator.
`DootahApplication.enrollTelemetry(ticket, callback)` exchanges it over TLS for a
30-day installation credential stored in app-private preferences. Ticket consumption
and installation/credential creation are transactional. Re-enrollment rotates and
revokes previous installation credentials within the same authorized scope. No
publisher token or secret is compiled into the APK. Public app/installation IDs alone
cannot write `/v1/events`, and installation credentials cannot read/control releases.

Registration includes installation ID, runtime, native version, SDK version and
embedded update UUID. The database binds organization/app/environment and tracks
selected Cloud release and last seen. Android is the only supported platform.
Reports are authenticated self-reports; this is not device attestation. Compromised
native apps/devices can lie about events. General anonymous consumer enrollment and
automatic credential renewal are not implemented; operator/backend enrollment is the
explicit integration requirement.

## Events and native provenance

The SDK subscribes to the existing updater state machine before it starts. It sends
check, availability, download start and completed download events from those native
notifications. `update_activated` means a selected and validated portable snapshot,
not an already rendered frame. `update_healthy` is emitted only after the existing
native frame acknowledgement accepts that launch. Rejection/native fallback uses
the existing rejection path. Persisted rollback intent plus subsequent embedded
selection produces `rollback_applied`, attributed to the displaced remote release.

Supported types: `update_checked`, `update_available`, `download_started`,
`download_completed`, `verification_failed`, `update_activated`, `update_healthy`,
`update_rejected`, `rollback_applied`, `native_fallback`.
`verification_failed` is classified from the updater's persisted structured log: a
check/download error is a verification failure only when expo-updates recorded an
`UpdateCodeSigningError` entry (written solely by its manifest and directive signature
checks) during this process and after the last reported one. Generic network errors are
never labelled verification failures. Classification runs about one second after the
error event, because the log is appended asynchronously; like all telemetry it is
best-effort and never affects rejection, which happens regardless.

A batch is `{events:[...]}`. Each event has UUID `id`, `installationId`, nullable
signed transport `updateId`, `type`, UUID `activationId`, ISO `timestamp`,
`runtimeAbi` (2 or 3), `logicAbi:1`, and `metadata:{}`. Metadata is deliberately an empty
allowlist in this API, so arbitrary logging/stack traces cannot enter ingestion.
Cloud maps transport UUIDs to its own tenant-scoped releases; embedded events have
no OTA release. A foreign/unknown update UUID is rejected, not attached by guesswork.

## Bounds and failure behavior

- 32 events/batch, 32 KiB serialized event body, strict field/type/ABI validation.
- Timestamps at most seven days old or five minutes ahead; both occurrence and
  ingestion time are retained.
- Unique `(organization, installation, event ID)` deduplication; identical retries
  do not change counters, conflicting event-ID reuse returns 409.
- PostgreSQL-backed shared limits: 120 events/minute/credential and 10,000 per
  organization, plus authenticated request and login/delivery ingress limits.
  Limits survive replicas/restarts; old limiter windows are cleaned periodically.
- Native queue: at most 64 events in app-private preferences; oldest is dropped
  on overflow. Native sender batches 32, retries on a ten-second schedule, uses
  three-second connect/read deadlines and bounds response bytes to 8 KiB.

Telemetry executes on a separate native executor, never the UI or portable sandbox.
Cloud failure does not select an update, bypass recovery, acknowledge health, or
block rendering. An interrupted process can retry queued events and server deduplication
handles that. Preference persistence is asynchronous, so sudden process termination
can lose recent events. Expired credentials, clock skew or a rejected stale/invalid
batch can require re-enrollment/operational attention; no lossless analytics promise
is made. Retention jobs and automatic credential renewal remain limitations.

## Metrics

All metrics state a 30-day window and server `asOf` time; zero denominators return
null percentages. Counts are distinct installations, not repeated HTTP requests.
SQL aggregates/indexes avoid loading all raw events into the API process.

| Metric | Meaning |
| --- | --- |
| Compatible | Enrolled installations in this app/environment/runtime seen in 30 days |
| Eligible | Compatible population inside the release's current deterministic threshold |
| Downloaded / activated / rejected / rollback count | Distinct installations reporting the respective release event in the window |
| Healthy | Activated installations with a subsequent health event for the same installation/release/activation ID |
| Adopted | Eligible installations whose newest occurrence-time selection report names this release |
| Adoption % | Adopted / eligible × 100 |
| Health % | Healthy / activated × 100 |

Pause does not erase the eligible denominator. Policy changes recompute it using
the same hash as delivery. An installation outside a decreased percentage can still
run cached code; it is excluded from the eligible-cohort adoption numerator. Lifetime
health/adoption is not inferred. A late selection report cannot replace a newer
occurrence-time active state. Health means “an accepted native frame was reported,”
not continued correctness, crash-free operation or current connectivity.
