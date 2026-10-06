> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). This records one local
> clean-room pre-publication alpha acceptance, not a public production release.

# Phase 9G — CLEAN-ROOM PRE-PUBLICATION ALPHA ACCEPTANCE

**PASS — completed 2026-10-05 (Asia/Kolkata).** The corrected clean-room attempt
proved native → signed OTA → signed native rollback on one Samsung installation,
using the staged public-alpha candidate and the documented ngrok/Caddy path.
GitHub-hosted V2 CI remains **UNVERIFIED**. Phase 9H was not started.

Starting HEAD: `a5006d8c333e19cda80f303822071432beac816f`.
This contains the authorized Caddy correction; its separate
[small preflight](PHASE9G_CADDY_PREFLIGHT.md) passed before this acceptance began.
The acceptance source checkout was initially clean and outside the repository.
The main workspace's three preexisting untracked historical attempt reports were
left unchanged and were not build inputs.

## Historical attempts remain failures

| Attempt / finding | Historical classification and disposition |
| --- | --- |
| First attempt, 9G-DOC-001 | DOCUMENTATION_BUG — private HTTPS onboarding missing; preserved as failed |
| Second attempt, 9G-DOC-002 | DOCUMENTATION_BUG — documented private-IP TLS negotiation failed; preserved as failed, route not qualified |
| Third attempt, 9G-BUG-003 | DOOTAH_GENERIC_BUG — non-root Cloud could not read private-umask build inputs; fixed separately in `99ec6e6` |
| Permission-fix smoke, 9G-DOC-004 | DOCUMENTATION_BUG — direct Cloud host listener unavailable; preserved in [original smoke evidence](PHASE9G_PERMISSION_FIX.md), corrected separately in `a5006d8` |
| Fourth attempt | ENVIRONMENT_DEVICE — interrupted during staging; temporary workspace/logs were absent on continuation and Docker was stopped. No APK was installed. [Interruption record](evidence/phase9g-attempt4-interrupted-20261004/result.json) preserves the limitation |
| Fifth, corrected clean-room attempt | PASS — this report; entirely new workspace/state/app after the interruption |

No earlier failure was rewritten as a pass. No new documentation or generic product
bug was found in the fifth attempt; no implementation change was made during it.

## Candidate, app and isolation

Public SDK: **0.1.0-alpha.1**, 19 freshly staged Maven publications, validated notices,
checksums and dependency metadata. The producer used the official `stage.sh`; the
consumer resolved Dootah exclusively from that stage using a separate empty Gradle
home. Neither `0.7.5-local`, `mavenLocal()` nor `android-sdk/build/maven` supplied the app.

[SHA-256 manifest](evidence/phase9g-attempt5-20261004/artifact-SHA256SUMS), itself hashed:

```text
3cf1dfe495eed10a9031a213591732501325ea51cbdf42ae4fe51b6b49387790
```

Fresh app: **RelayAlpha / `example.dootah.relayalpha`**. Toolchain: JDK 21,
Gradle 9.3.1, AGP 8.12.0, Kotlin/Compose plugin 2.1.20, compile/target SDK 36,
minSdk 29, arm64-v8a. Runtime ABI **2**, Logic ABI **1**, renderer **BasicText / Option A**.
Dootah-owned code remains Apache-2.0. The app was authored from the public integration
snippets with a normal native counter alongside CheckoutScreen; it reused no test
application or internal native-consumer fixture. Only generic Gradle wrapper files
were copied from the publishing tool.

New producer and consumer Gradle homes, npm cache, Go cache volumes, Android debug
signing key, Cloud/PostgreSQL/xprem state, operator credentials, per-app OTA signing
identity and retained release state were created under umask 077. Matching Docker
image layers and installed upstream toolchains were reused; no prior Dootah Gradle
artifacts or backend/release state were consumed. This is not bit-for-bit build
reproducibility evidence.

The supported topology was:

```text
Android / CLI -> ngrok HTTPS -> 127.0.0.1:18080 Caddy
                            -> private Cloud -> private xprem/PostgreSQL
```

Only Caddy published a host port. Docker inspection showed no Cloud/xprem/PostgreSQL
host bindings. Normal curl and Node TLS verification returned `/live` and `/ready`
200, and the customer CLI authenticated through the same URL before SDK staging.
Cloud, binding, xprem advertised origin, CLI and Android `/manifest` host agreed
before the accepted APK build. The private xprem origin remained `http://xprem:3100`.
Ngrok supplied HTTPS transport trust only; the separate xprem-generated public OTA
certificate was embedded in the APK. No ngrok token was read or printed, TLS bypass
used, custom Android trust override added, or cleartext app permission enabled.

## One-install device evidence

Physical Samsung **SM-E426B, Android 13**, connected through adb. Exactly **one APK
installation**, **zero reinstalls**, **zero data clears**. Retention/import verified
the baseline record; environment creation and idempotent contract registration used
the supported customer CLI. The native ticket dialog enrolled the installation.
An initial UI submission did not enroll; a fresh ticket was submitted using current
button coordinates after keyboard dismissal, as permitted by the documented retry
flow. No runtime or enrollment workaround was used.

Accepted and installed APK SHA-256:

```text
23fe0779a501edd9059a88decd868bba7e5e82398dd53d12707a04a2ab790eb2
```

Device `firstInstallTime`: **2026-10-04 22:46:30**. Installed APK bytes and this
timestamp matched the baseline after online restart and after rollback/final checks.

The [native baseline](evidence/phase9g-attempt5-20261004/native-baseline.png) showed
**Discount: 10**; the native counter incremented independently. The only supported
OTA edit changed local helper `p * 10 / 100` to `p * 20 / 100` inside the existing
application-module CheckoutScreen. Analysis found one portable changed function.
No hook ID, JavaScript, retained contract, manifest or native capability was edited.

Cloud publication completed with a verified signed receipt; customer CLI rollout
enabled the controlled environment at 100%. The same APK downloaded the update,
then showed [Discount: 20](evidence/phase9g-attempt5-20261004/ota-visible.png).
The [native counter still worked](evidence/phase9g-attempt5-20261004/ota-native-counter.png).
Online restart retained the OTA. During the documented retrieval outage, only Cloud
was stopped; public health returned 502, while the [cached OTA](evidence/phase9g-attempt5-20261004/offline-restart.png)
and native counter still worked after restart. Device radios remained enabled.
Cloud was restored and public readiness returned 200 afterward.

[Native telemetry](evidence/phase9g-attempt5-20261004/telemetry.json) contains all six
required events: `update_checked`, `update_available`, `download_started`,
`download_completed`, `update_activated`, `update_healthy`. Remote activation and
health share an activation ID. This is one genuine enrolled installation, without
synthetic event injection. Frame health is not crash-free analytics; the API's
`crashFree` value is null.

Customer CLI rollback completed with a verified signed `rollBackToEmbedded` receipt.
After an online check and restart, [Discount: 10 returned](evidence/phase9g-attempt5-20261004/rollback-native.png).
`rollback_applied` attributed the restoration to the displaced release. A further
offline restart stayed native. The final release state is `rolled_back`.

Changing `val amount` to unsupported mutable `var amount` made **both analyze and
publish exit 1** with `Only immutable locals/helpers`. Diagnostic IR contained zero
overrides and no generated JS; the whole publication was refused. The Cloud release
list was identical before/after, and the installed native APK stayed unaffected.
This expected refusal is **UNSUPPORTED_BY_DESIGN**, not a new product bug.

## Sandbox and scope audit

[Device/service evidence](evidence/phase9g-attempt5-20261004/sandbox-evidence.json)
ties the app's service binding to WebView's JavaScriptSandbox service under a distinct
isolated UID. Native logs report a non-embedded update with `sandbox=true` and native
Compose frame health acknowledgement. The app's readable process maps contained no
loaded Hermes, React Native or V8 library. Consumed SDK source confirms AndroidX
JavaScriptSandbox evaluation with a fresh bounded isolate and string-only inputs/results.
No RN NativeModule registry, Expo module registry, callbacks, arbitrary Android/JVM
objects or new capabilities are exposed to remote logic. Installed capabilities
remain `logic.pure.v1` and `compose.basicText.v1`.

This combines device observation with the existing source boundary; it is not a new
adversarial capability test or broad security certification. Production-source search
found **zero** references to the test app/package. No DB edits, manual hook IDs,
undocumented product helpers/workarounds or app-specific Dootah behavior were used.
Ordinary adb/UI operations and documented read-only Cloud APIs collected evidence.

## Final acceptance checklist

| Requested outcome | Result |
| --- | --- |
| Fresh Gradle cache | YES — separate empty producer and consumer homes |
| Fresh Cloud/xprem/PostgreSQL | YES |
| README-only onboarding and documented integration | PASS — README and linked maintained guides |
| Native baseline | PASS |
| Public-alpha artifact candidate consumption | PASS |
| Release retention/import | PASS |
| Contract/environment registration | PASS |
| Telemetry enrollment | PASS |
| Supported Kotlin analyze | PASS |
| Cloud publication | PASS |
| Signed OTA / visible native Compose OTA | PASS / PASS |
| Online restart | PASS |
| Offline cached restart | PASS — documented retrieval outage |
| Signed rollback / native restoration | PASS / PASS |
| Unsupported-edit refusal | PASS |
| Sandbox isolation | PASS |
| Same APK / same install timestamp | PASS / PASS |
| Manual DB changes / manual hook IDs | NO / NO |
| Undocumented helpers/workarounds | NO |
| App-specific Dootah hacks | ZERO |
| New documentation issues / generic product bugs | NONE / NONE |
| External-publication / external-production-deployment claims | NONE / NONE |
| Final classification | PASS |
| Clean-room pre-publication alpha acceptance complete | YES |
| Can Phase 9H begin? | NO — hosted V2 CI remains UNVERIFIED; no Phase 9H action authorized or started |

Public docs used for this attempt were **CORRECT** for the executed flow. Hosted CI
was checked independently; public workflow metadata returned HTTP 404 and provided
no successful hosted run evidence. Local gates cannot establish hosted CI success.
Fresh CLI tests passed 22/22; xprem passed 345 tests with the four documented Azure
exclusions; the production image permission audit passed. The stage validated all
19 publications and the fresh consumer built/retained successfully. No full 450-test
suite rerun or public registry validation is claimed.

## Cleanup, preserved evidence and claim boundary

The app was left native and force-stopped. Retrieval was restored before teardown;
device networking was never changed. No adb reverse rules were created. The temporary
device UI dump was removed. This attempt's ngrok process, containers, networks and
volumes, including Go caches, were stopped/removed. Operator secret inputs, private
signing key and customer session/token files were removed; unrelated resources and
the user's ngrok configuration were untouched.

The exact staged candidate and retained native release were archived privately
outside Git and temporary storage. No APK, private key, token, raw device/server log
or database is committed. Sanitized [result](evidence/phase9g-attempt5-20261004/result.json),
screenshots, native event fields, signature receipts and hashes remain reviewable.

This proves **clean-room HTTPS OTA acceptance through a publicly trusted tunnel**.
It does not prove Maven Central acceptance, JFrog publication, public artifact
availability, production self-host deployment, custom-domain/public production TLS,
real-domain deployment or Internet-scale operation. Those external validations remain
deferred release work, alongside successful GitHub-hosted V2 CI. **Stop after 9G.**
