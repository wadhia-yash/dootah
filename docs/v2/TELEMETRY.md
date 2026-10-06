> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# Installations, telemetry and metrics

The [Phase 9C enrollment workflow](../../v2/publishing/ONBOARDING.md#telemetry-enrollment)
provides a supported ticket command, a backend-only helper and a native integration
example. Tickets still use the existing one-use API and the SDK exchange below; no
anonymous enrollment or native-runtime change is introduced.

Phase 7 uses native SDK `0.7.0-local`; Runtime ABI 2 / Logic ABI 1 remain unchanged.
See [Cloud architecture](CLOUD_ARCHITECTURE.md) for the explicit authorization of the
new baseline and [acceptance](evidence/phase7-20260928/acceptance.json) for device results.

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
MVP's explicit integration requirement.

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

Device check (SDK `0.7.5-local`, debug native-consumer fixture, Samsung SM-E426B, local
probe server through `adb reverse`): a manifest signed by the wrong RSA key produced the
updater's `UpdateCodeSigningError` entry while the state event carried only `Failed to
download remote update`; `verification_failed` was queued once per rejection (two separate
rejected launches produced two events). An HTTP 500 launch in between produced
`update_checked` but no `verification_failed`, and did not re-count the earlier rejection.
Each launch stayed native and reported `update_healthy`.

A batch is `{events:[...]}`. Each event has UUID `id`, `installationId`, nullable
signed transport `updateId`, `type`, UUID `activationId`, ISO `timestamp`,
`runtimeAbi:2`, `logicAbi:1`, and `metadata:{}`. Metadata is deliberately an empty
allowlist in this MVP, so arbitrary logging/stack traces cannot enter ingestion.
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

The acceptance environment also contains synthetic enrolled API-test installations.
At the captured live metric sample, one of four eligible installations had adopted
the release: **25% adoption**, with one activated and healthy device: **100% frame
health**. These are honest different denominators, not a claim of full fleet adoption.
The curated event summary filters the actual SDK/native version, separating physical
reports from synthetic test events. The Phase 6 APK's local logs were never imported
as Cloud health events; server asset responses were never counted as downloads/health.


## Phase 8 observation

Real-app acceptance collected genuine device events for Mihon, Read You and JetNews,
including `rollback_applied`. Real code-signing rejections (a tampered manifest and a
tampered directive) were rejected by Expo, but the state-change event only carries
`Failed to download remote update`, so **`verification_failed` was never emitted**. The
classifier is therefore not functional for real verifier failures; fixing it needs a
richer signal from the pinned updater and is deferred. Health for an override whose
function is not on the first screen is reported when that function first renders.
