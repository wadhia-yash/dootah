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
and [security model](../security/README.md). Historical rollout/telemetry test records
are indexed under [history](../history/README.md).
