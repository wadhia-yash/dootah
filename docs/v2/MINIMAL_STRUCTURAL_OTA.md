> Engineering history / dated acceptance record. Current support is documented in
> [the support boundary](../reference/support.md). See the actual results and validation limits below.

# Minimal structural OTA — physical acceptance record

Dated engineering record for 2026-10-10. This records bounded physical-device acceptance; it is not a public SDK release claim.
The public support and security references distinguish this working-tree extension
from the published alpha.1 / ABI 2 baseline.

## Verified scope and plan

The parity audit and Tier 1 feasibility report were read completely. The actual
original Mihon `PreferenceGroupHeader` contains Box, Material3 Text, secondary,
bodyMedium, integer Dp padding and the separate same-package 16.dp constant.
The analyzer can lower that original source unchanged. The separate existing
`mihon-dootah` checkout contains a BasicText experiment; neither original checkout
was edited. An isolated Mihon worktree retains the exact original header.

The feasibility report's claim that Cloud needs only a classpath change is incomplete:
telemetry ingestion, retained records, the publishing CLI, customer importer and
Cloud release storage enforce ABI 2.
These must change alongside the renderer. The reports' broader Row/Column vocabulary
is deliberately outside this task. The later feasibility recommendation takes priority
over the parity report's suggestion to return a tree from JavaScript.

Exact implementation paths (all relative to the repository root):

- `v2/portable/src/main/java/dev/dootah/portable/PortableProgram.java`
- `v2/portable/src/main/java/dev/dootah/portable/UiTree.java` (new)
- `v2/publishing/src/main/groovy/dev/dootah/publish/UiLowering.groovy` (new)
- `v2/publishing/src/main/groovy/dev/dootah/publish/SourceAnalyzer.groovy`
- `v2/publishing/src/main/groovy/dev/dootah/publish/Contract.groovy`
- `v2/android-sdk/runtime/src/main/java/dev/dootah/runtime/ComposeDispatch.kt`
- `v2/android-sdk/runtime/src/main/java/dev/dootah/runtime/ComposeEntry.java`
- `v2/android-sdk/runtime/src/main/java/dev/dootah/runtime/DootahApplication.kt`
- `v2/android-sdk/runtime/src/main/java/dev/dootah/runtime/CloudTelemetry.kt`
- `v2/android-sdk/runtime/src/main/assets/dootah-capabilities.json`
- `v2/android-sdk/runtime/build.gradle`
- `v2/android-sdk/gradle-plugin/src/main/groovy/dev/dootah/gradle/DootahRetainRelease.groovy`
- `v2/publishing/customer.mjs`, `v2/publishing/cli.mjs`
- `v2/cloud/server.mjs`, `v2/cloud/validator.sh`, `v2/cloud/config.mjs`,
  `v2/cloud/admin.mjs`, `v2/cloud/migrations/007.sql`
- `v2/deploy/build.sh`, `v2/deploy/Cloud.Dockerfile` (package the shared validator class)

Associated test fixtures, ABI gate metadata and current support/security documentation
also change. Signing, Expo delivery, sandbox implementation, hook instrumentation and
rollback algorithms are unchanged. No new backend or infrastructure is introduced.

## Contracts

Runtime ABI 3 rejects ABI 2 artifacts. Logic ABI remains 1. `tryRenderV2` and its
String/Boolean/Int scalar signature remain unchanged. Retained release record schema
is still 1; the publisher installed-contract schema/IR ABI is 3 and dispatch ABI is 2.
An incompatible native baseline requires a new runtimeVersion and environment.
Existing ABI 2 telemetry remains accepted alongside ABI 3 telemetry. Existing signed
ABI 2 releases remain stored and deliverable; the upgraded publisher/validator only
accepts new ABI 3 publications, so publishing new ABI 2 artifacts requires the matching
older toolchain/service version. Migration 007
preserves ABI 2 release rows and permits ABI 3. Publication persists the validated
artifact ABI, and startup/readiness require schema 7. Apply the migration with the
matching Cloud binary; an old schema-6 Cloud binary cannot start against schema 7.

BasicText expressions remain available in ABI 3. Structural overrides instead carry
`parameters`, `slots` and `tree`. Each slot contains its declared scalar type and a
Logic ABI 1 expression. The native tree references each slot exactly once. All nodes,
branches, fields, tokens, modifiers, scope requirements and expression types validate
before execution. Capability requirements must equal the program's used installed
capabilities. Valid trees become immutable native objects.

The sandbox receives scalar inputs and returns only a JSON-encoded scalar array.
The selected tree branch controls which slots evaluate. Inactive slots must be null;
unused or aliased slot references are refused. Unknown native calls cannot be encoded.

Limits: 64 UI nodes, depth 12, eight modifiers per node, sixteen slots including at most
eight text slots, integer Dp 0–1024. Worst-case escaped text fits the unchanged 16 KiB
sandbox result bound. Existing 64 KiB artifact/source, 512 operation, string, input,
heap, timeout and invocation limits remain enforced. No handler, state or host-object
capability is added.

Supported structural source: one root Box or Material3 Text; nested Box/Text and child
if/if-else; ordered fillMaxWidth(), padding and BoxScope.align. Only secondary/tertiary
colour and bodyMedium/labelSmall typography tokens are included. Theme resolution uses
the caller's MaterialTheme. Missing color/style use Material3's native default values.
Frozen constants must be unambiguous same-package retained top-level immutable Dp
literals, with no getter/delegate/receiver or computed initializer.

Not supported: Row/Column, root conditionals, new imports, resources, app composables,
app extension tokens/modifiers, callbacks, state, Modifier/object inputs, fractional or
computed Dp, external-module constants, arbitrary Text arguments, or Tier 2/3 features.

## Reproduce focused checks

Use JDK 21 and the Android SDK from CONTRIBUTING. Run from the repository root:

```sh
v2/publishing/gradlew -p v2/publishing test installDist
node --test v2/publishing/customer.test.mjs v2/publishing/portable.test.mjs
v2/cloud/validator.sh
v2/publishing/gradlew -p v2/android-sdk/gradle-plugin test
v2/runtime-spike/android/gradlew -p v2/runtime-spike/android -PdootahSdk \
  -PreactNativeArchitectures=arm64-v8a :runtime-v2:testReleaseUnitTest \
  :runtime-v2:connectedDebugAndroidTest
node --test v2/android-sdk/tests/security/*.test.mjs
```

The instrumented fixture compares native versus lowered screenshots under light/dark
colours, custom typography and LTR/RTL. Its artifact must equal the analyzer output
from the ordinary Kotlin fixture, enforced in publisher tests. It uses the real sandbox.
This fixture is not evidence of signed delivery or an application OTA.

## Physical Mihon acceptance procedure

1. Use an isolated checkout with the exact original header and the upgraded local SDK.
   Keep the existing Dootah Application integration, public signing certificate and
   delivery URL. Use a distinct test package, new runtimeVersion and new channel.
2. Build/retain one R8 release baseline, import its record and register its immutable
   contract with the existing V2 Cloud service running the updated shared validator.
   Use private credential files and the existing signing identity; do not disable TLS
   or update signature verification.
3. Install that baseline once. Record `adb shell pm path PACKAGE`; pull its base APK
   and compute SHA-256. Record `firstInstallTime`, `lastUpdateTime`, versionName and
   versionCode from `adb shell dumpsys package PACKAGE`.
4. Open Settings > Appearance. Save private screenshots/UI hierarchy and confirm the
   original header. Confirm the native layout before publishing any replacement.
5. Make only ordinary header-body source edits, using already installed imports.
   Publish OTA-1 adding Text("NEW") with tertiary, labelSmall and
   Modifier.align(Alignment.CenterEnd).padding(horizontal = PrefsHorizontalPadding).
   Publish OTA-2 wrapping it in `if (title == "Theme")`. Publish OTA-3 removing the
   original title Text. Analyze and publish each through the customer CLI against the
   retained baseline; never rebuild or reinstall between these updates.
6. Capture each selected update ID, signature verification/operation receipt, committed
   frame acknowledgement and visible change. Compare original title bounds/colours
   where retained. Check dark mode and native typography. Repeat installed APK hash
   and installation timestamp checks at every stage.
7. Restart offline and confirm cached OTA. Request signed rollback to embedded,
   restart and verify original headers; restart offline again and confirm native.
8. Verify unsupported Icon/new Row import edits refuse the entire publication with no
   release created. Verify wrong ABI/malformed artifacts fail Cloud and device
   validation; verify tampering fails signature/hash validation and keeps native/LKG.

All private artifacts and raw logs stay outside Git. Do not declare PASS until this
entire signed OTA sequence is visible on the physical device without reinstalling.

## Actual results

**Status: PASS for the bounded structural OTA milestone.** Physical acceptance
completed; no subsequent milestone or public SDK release is authorized by this result.

Physical test device: Vivo 1909, Android 12 / API 31, ARM64. Samsung SM-E426B /
Android 13 also passed the renderer instrumentation earlier in this session.

One R8 Mihon 0.20.4 baseline was installed as `app.mihon.dootah.structural`.
Its original header source is byte-identical to the preserved original Mihon header
(SHA-256 `191187a3dce62c1f5829501c71da40ea5cb779c0a6b3438a61025867c7db5d2d`).
The separate existing BasicText experiment also remains unchanged.

Installed APK SHA-256:
`a9e4c3552d21f29a13eaaf7936f42e91a59fb01fd00c2fe48d72d8efbcb9c8bb`.
`firstInstallTime` and `lastUpdateTime` both remained `2026-10-10 14:15:30`
(device-reported local time) across baseline and all three structural OTAs.

| Stage | Signed update ID | Observed on Vivo |
| --- | --- | --- |
| Baseline | Embedded | Original Theme and Display titles, no labels |
| OTA-1 | `0900f3ed-9ba6-842a-4a8c-8e5de870511c` | Right-aligned NEW on both headers |
| OTA-2 | `163a5363-068c-2d99-7b1e-bedc41b7a591` | NEW only beside Theme; Display has no label |
| OTA-3 | `ea7cfaf3-c02f-1236-571c-25fdedf90689` | Both original titles removed; conditional NEW remains |

OTA-1's original title pixels match the native baseline exactly in both light and
dark modes. Theme bounds remain `[48,318][181,378]`; the added label uses
`[955,324][1032,372]`. Its observed solid text colour changes from `#605a7c` to
`#cac1ea` with the installed dynamic theme. OTA-2's true and false branches were
captured in both themes. Runtime logs confirm sandbox selection and committed-frame
health. Each manifest signature and delivered artifact hash verified against the
installed certificate; altered manifest bytes failed the same signature verification.

The actual customer CLI refused an unsupported `maxLines` source edit before
publication. Live Cloud rejected ABI 2, unknown-node and oversized artifacts (422),
with no additional release. A synthetic telemetry API probe accepted ABI 2/3 and
rejected 1/4/string-3; it is not represented as device telemetry.

The existing acceptance Cloud service was upgraded in place using an image overlay
on its existing image; no signing keys, delivery data or database were replaced.
Migration 007 succeeded and preserved the two prior ABI 2 release rows. A transactional
database test accepted ABI 2/3 and rejected 1/4. New releases record runtime ABI 3.
This local image exercise is not a clean release-image or hosted CI claim.

The first offline attempt disconnected wireless ADB and produced no files; it was
not counted as a pass. USB was unavailable. Successful offline checks used Vivo's
per-app Network Management with **both Wi-Fi and mobile data OFF for this test app**,
keeping wireless ADB available. Captures record those settings. Vivo also displayed
its network-restriction dialog; Disallow was selected and recorded.

- Offline rollback persistence: a force-stop/relaunch selected embedded update
  `af72b798-f375-4d98-8858-a199b56cd845`, acknowledged the native frame and rendered
  both original titles without NEW while the app's network access was blocked.
- Offline OTA persistence: the same OTA-3 source was published again as signed update
  `9a03a59b-1560-dc95-3f43-896d986c3ffb`. After download, network blocking and a
  force-stop/relaunch, the cached structural UI rendered with NEW and without titles.
  The runtime acknowledged its frame while Cloud was offering a signed rollback.
- Signed rollback: both rollback directives verified against the installed certificate.
  The first rollback visibly restored the original native header with unchanged APK
  hash and installation timestamps. The final repeated rollback also selected and
  acknowledged the embedded update and restored both original headers.

App network permissions were restored to their initial ON state after the offline
checks. The test instrumentation APK was removed, the original System theme restored,
and the acceptance checkout header restored from its archived baseline. The final
Mihon installation remains on the original native UI. The radio-disconnection attempt was superseded by these per-app offline
checks; it is retained as an unsuccessful test setup, not hidden or counted twice.

Focused checks completed:

- Publisher: 34 passed; CLI/portable Node: 10 passed.
- Gradle plugin: 9 passed; real Compose compiler fixtures: 4 passed.
- Runtime unit: 7 passed; renderer instrumentation: 2 passed on Samsung and 2 on Vivo.
- Expo NativeHealthTest: 3 passed in a fresh forced test execution.
- Consumer fixture debug and R8 release builds passed (13 / 11 hooks).
- Security Node: 13 passed; CI helper Python: 28 passed; docs unit: 5 passed.
- Cloud core/client unit tests: 8 passed. Unconfigured integration suites explicitly
  skipped; the live probes above ran separately against the acceptance environment.
- Deployment configuration: 10 passed, including schema 7 readiness and refusal of
  old/new schema versions and excessive database privileges.
- Exact documented Kotlin examples passed when run directly; `git diff --check` passed.

The documentation validator reports only missing historical banners in the two
user-supplied reports; those untracked files were preserved. Initial build issues
(JDK selection, test APK packaging and the isolated consumer's Maven group filter)
were corrected. The Vivo rerun used the already-built instrumentation APK after a
slow Gradle configuration attempt. The first OTA-2 launch displayed cached OTA-1
while downloading; verification used the subsequent launch with OTA-2 selected.

The three clean committed-tree CI gates and hosted Actions have not been run for this
uncommitted work. Private APKs, retained classes/source, screenshots, logs, receipts,
operator state and reproduction scripts remain outside Git under the local acceptance
workspace. Do not publish those raw artifacts.


## Changed-file inventory

- `docs/concepts/architecture.md`
- `docs/reference/support.md`
- `docs/security/README.md`
- `docs/v2/MINIMAL_STRUCTURAL_OTA.md`
- `tools/docs/examples.groovy`
- `tools/docs/validate.py`
- `v2/android-sdk/gradle-plugin/src/main/groovy/dev/dootah/gradle/DootahRetainRelease.groovy`
- `v2/android-sdk/gradle-plugin/src/test/groovy/dev/dootah/gradle/RetentionTest.groovy`
- `v2/android-sdk/runtime/build.gradle`
- `v2/android-sdk/runtime/src/androidTest/AndroidManifest.xml`
- `v2/android-sdk/runtime/src/androidTest/assets/header.json`
- `v2/android-sdk/runtime/src/androidTest/java/dev/dootah/runtime/StructuralFidelityTest.kt`
- `v2/android-sdk/runtime/src/main/assets/dootah-capabilities.json`
- `v2/android-sdk/runtime/src/main/java/dev/dootah/runtime/CloudTelemetry.kt`
- `v2/android-sdk/runtime/src/main/java/dev/dootah/runtime/ComposeDispatch.kt`
- `v2/android-sdk/runtime/src/main/java/dev/dootah/runtime/ComposeEntry.java`
- `v2/android-sdk/runtime/src/main/java/dev/dootah/runtime/DootahApplication.kt`
- `v2/android-sdk/tests/sandbox/src/main/java/dev/dootah/sandboxproof/SandboxProof.java`
- `v2/android-sdk/version.properties`
- `v2/ci/pins.json`
- `v2/ci/required-suites.json`
- `v2/ci/test_gates.py`
- `v2/ci/versions.py`
- `v2/cloud/admin.mjs`
- `v2/cloud/config.mjs`
- `v2/cloud/migrations/007.sql`
- `v2/cloud/server.mjs`
- `v2/cloud/test/api.test.mjs`
- `v2/cloud/test/database.test.mjs`
- `v2/cloud/test/release.test.mjs`
- `v2/cloud/validator.sh`
- `v2/deploy/Cloud.Dockerfile`
- `v2/deploy/acceptance-client.mjs`
- `v2/deploy/acceptance.py`
- `v2/deploy/build.sh`
- `v2/deploy/config.test.mjs`
- `v2/portable/src/main/java/dev/dootah/portable/PortableProgram.java`
- `v2/portable/src/main/java/dev/dootah/portable/UiTree.java`
- `v2/publishing/acceptance.py`
- `v2/publishing/cli.mjs`
- `v2/publishing/customer.mjs`
- `v2/publishing/customer.test.mjs`
- `v2/publishing/src/main/groovy/dev/dootah/publish/Contract.groovy`
- `v2/publishing/src/main/groovy/dev/dootah/publish/SourceAnalyzer.groovy`
- `v2/publishing/src/main/groovy/dev/dootah/publish/UiLowering.groovy`
- `v2/publishing/src/test/groovy/dev/dootah/publish/CloudContractTest.groovy`
- `v2/publishing/src/test/groovy/dev/dootah/publish/LogicTest.groovy`
- `v2/publishing/src/test/groovy/dev/dootah/publish/StructuralUiTest.groovy`
- `v2/publishing/src/test/resources/structural/Header.kt`
