# Delivery, rollout and rollback

Publication is asynchronous. `dootah publish` returns operation/release identifiers;
inspect them until the operation succeeds and the release is ready. Publishing does
not enable rollout. Mutations use the latest release version; stale versions return
409. Keep explicit idempotency keys for ambiguous publish/rollback retries.

Rollout uses deterministic installation buckets. A percentage controls new offers to
compatible installations; it is not a promise of immediate fleet adoption. Pause
stops new offers and resume restores offer eligibility. Neither evicts cached bytes.
The native updater normally downloads during a check and activates on a subsequent
launch. Keep the same APK installed while verifying an OTA.

Rollback requests a signed `rollBackToEmbedded` directive through Cloud and xprem.
Wait for its operation, allow an online update check, then restart and confirm the
original native behavior. Check again offline. It is not an instantaneous remote
kill switch and does not undo effects of earlier business decisions. Offline clients
may keep their last accepted cached update until they receive a valid directive.

The installation identifier/credential used by Cloud requires native telemetry
enrollment. Obtain a short-lived one-use ticket from an authenticated customer
backend and call `DootahApplication.enrollTelemetry`. Ticket lifetime is ten minutes;
the installation credential lasts 30 days. Renewal needs a fresh ticket; no automatic
long-term renewal or anonymous production enrollment is supplied. Never put publisher
credentials in the APK. See [enrollment integration](../../v2/publishing/ONBOARDING.md#telemetry-enrollment).

Health acknowledges a native rendered frame (the overridden function for a remote
release). It is not crash-free session analytics or proof that all screens work.
A target off the first screen may be acknowledged much later; the maintained update
patch has no first-render deadline. Enrollment, telemetry and reporting are best
effort and must not block native startup/navigation.

Use the [quickstart commands](../getting-started/quickstart.md#publish-roll-out-and-verify)
and [security model](../security/README.md). See [telemetry](../reference/telemetry.md) for event and metric definitions.

## Cohort

For canonical lowercase installation and immutable Cloud release UUIDs, hash the
UTF-8 string `dootah-rollout-v1:<releaseUUID>:<installationUUID>` with SHA-256.
Read the first eight bytes as an unsigned big-endian integer, reduce modulo 10,000,
and include when `bucket < percentage * 100`. Percentages are integers 0–100.
Missing/malformed installation identity fails closed for new offers.

There is no per-request randomness or mutable salt. Increasing percentage preserves
all earlier eligible installations. Modulo bias is negligible but this is not an
exact population quota, especially for small fleets. Node delivery and PostgreSQL
metrics implement the same function; cross-implementation tests compare 100 UUIDs,
and monotonic/deterministic tests exercise 2,000 installations at 0/1/10/25/50/100%.

xprem's existing bucketing is not layered on top: its internal isolated channels
hold complete signed releases; Cloud owns the externally visible selection policy.
This keeps the product contract independent of upstream release/branch identifiers.

## Distribution and concurrency

Publishing yields a ready release at 0%; the publisher never implicitly starts a
rollout. Setting rollout makes that release the environment head; 100% uses `active`,
less uses `rolling_out`. Switching heads pauses the former distributing release.
A durable rollback directive takes precedence over release offers until an explicit
new rollout replaces it. Every control operation requires authorization and the
current release version; stale changes return 409. A pending external operation
blocks conflicting environment mutations.

The gateway records an offer grant before returning a selected manifest. The grant
means offered, not downloaded or healthy. Asset retrieval checks the same tenant,
app, environment, installation and artifact hash, permitting in-flight downloads
after pause. Manifest responses are private/no-store. Public update requests do not
authorize control-plane or telemetry mutations. Policy/grants survive DB restart.

## Pause and resume

Pause stops **new offers**. It keeps the percentage and does not delete installed,
downloaded or cached content. Existing offer grants can finish asset download.
A paused phone can continue running the release.
Resume requires the same environment head and restores its stored percentage and
unchanged cohort. Reducing percentage similarly does not remove cached releases.

## Rollback and kill

Both call the proven authenticated xprem `rollBackToEmbedded` path using an isolated
operation channel. The worker verifies the resulting signature before transactionally
recording completion and making the directive the environment policy. Audit records
actor, time, source release, optional bounded reason, operation identity and result;
the private operation receipt retains signed directive parameters for reconciliation.
The public API exposes Cloud identities and operation state.

The directive affects the **whole matching environment/runtime**, including other
cached releases in that scope. A stale source-release rollback that would target a
newer environment head is rejected. The UI describes this scope. Previous remote
release restoration is not promised; this adapter restores embedded/native behavior.

Rollback marks its source `rolled_back`. Kill marks it `killed`, refuses subsequent
resume/rollout, and prevents asset grants from serving that killed release. Other
previously distributing records are paused. Later deliberate publication uses a new
release ID; it cannot resurrect the killed ID. Retries inspect an already-created
signed directive before issuing another. Ambiguous failures remain pending and
observable; no unsigned replacement command is produced.

Online clients receive state on their next check and apply it through the installed
updater lifecycle. Already-running code cannot be undone by a server request. Offline
clients cannot receive a new command. Cached bytes can remain on disk. Neither pause
nor kill is instant offline revocation, and rollout 0% is not a rollback.
