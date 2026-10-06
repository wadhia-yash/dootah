> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.
> [Current integration quickstart](../getting-started/quickstart.md).

# Dootah V2 Android SDK — Phase 3

2026-09-27: **Phase 3 PASS on physical Samsung SM-E426B.** The fresh Kotlin/Compose
consumer built with Node/npm/Metro blocked and passed signed OTA, online/offline
restarts and rollback on one accepted APK. Two failed baseline attempts and their
packaging corrections are disclosed below. No compiler hooks, Kotlin analysis,
transpilation or Phase 4 work is included.

## Artifact structure

`v2/android-sdk/runtime` produces `dev.dootah:runtime-v2:0.3.0-local`.
`v2/android-sdk/gradle-plugin` produces the `dev.dootah` plugin with the same version.
The producer publishes ordinary Maven AARs, POMs and Gradle module metadata into
`v2/android-sdk/build/maven`; artifacts are generated and are not committed.
There is no fat AAR, consumer npm installation, consumer code generation through
React Native, or dependency on a consumer JavaScript source tree.

The producer reuses the locked npm tree and release bundler in `v2/runtime-spike`.
Run `v2/android-sdk/build-sdk.sh` with JDK 21 and ANDROID_HOME to build the local SDK.
This is SDK maintainer tooling, not a consumer build step. It owns npm installation,
Metro/Hermes bootstrap compilation, Expo autolinking, the pinned native health
patch, and publication. The existing spike application itself is not installed.
All source-built Expo modules receive Dootah internal Maven coordinates; five npm
prebuilt Maven repositories are mirrored unchanged. Published dependency versions
come from the resolved release runtime graph, including RN's Hermes substitution.

## Fresh Android consumer

Phase 4 integration supersedes the manual title bridge below the Phase 3 artifact
boundary: current plugin/runtime coordinates are `0.4.0-local`; the unchanged
internal Expo dependencies remain `0.3.0-local`. See
[Compose instrumentation](COMPOSE_INSTRUMENTATION.md) for eligibility and evidence.

`v2/native-consumer` is an independent Gradle build. Its repositories include the
local SDK Maven directory for this unpublished proof, Google and Maven Central.
Both pluginManagement and dependencyResolutionManagement need that SDK repository.
A future hosted Maven repository can replace the local URL without changing the app.

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'org.jetbrains.kotlin.plugin.compose'
    id 'dev.dootah' version '0.4.0-local'
}
dootah {
    appId = '<self-hosted app UUID>'
    updateUrl = 'https://updates.example.com/manifest'
    channel = 'development'
    runtimeVersion = '<installed capability/runtime version>'
    publicCertificate = file('update-certificate.pem')
}
```

Use `dev.dootah.runtime.DootahApplication` as the manifest Application (or subclass
it for normal Kotlin application initialization). Extend `DootahActivity`, use normal
`setContent`, and call ordinary `@Composable` functions. The plugin adds the optional
entry dispatch automatically. No per-screen annotation, wrapper, state collection,
dispatch expression or frame modifier is required. The SDK owns native embedded
window-frame acknowledgement and attaches `dootahFrame` to its matched remote
renderer; a placeholder or a JS message alone cannot declare a remote update healthy.
Then run the normal `./gradlew assembleRelease` Android build.

The plugin adds the runtime dependency, literal manifest configuration, xprem
identity/channel headers, public trust certificate, signing metadata and embedded
manifest generation. It owns shared-native-library collision handling and selects
the supported ABI. The runtime AAR carries the Hermes bootstrap, permissions,
manifest template, consumer shrinker rules and transitive dependency metadata.
Expo Constants' producer `app.config` is replaced with `{}` so reusable artifacts
contain no sample endpoint, app identity or channel. Configuration is supplied
entirely by each consumer.

Defaults: channel `development`, signing key ID `main`, RSA v1.5/SHA-256,
check on every launch, zero launch wait. Downloads activate on the next process
launch. HTTPS is required. `allowLocalHttp = true` permits only loopback/emulator
host URLs for device tests; the sample also permits Android cleartext traffic.
Never copy that test manifest policy into a production app.

## Dependency inventory

| Component | Classification | Packaging |
| --- | --- | --- |
| React Native 0.86.3 | runtime | Maven transitive AAR; hidden ReactHost, no surface |
| matching Hermes 250829098.0.17 | runtime | com.facebook.hermes AAR, native engine |
| expo 57.0.25 | runtime | Dootah internal AAR with generated module registry |
| expo-updates 57.0.23 | runtime | patched internal AAR, Room, signing, download/cache/recovery |
| expo-brownfield 57.0.23 | runtime | internal AAR, validated Kotlin message delivery |
| expo-modules-core 57.0.19 | runtime | internal AAR including JNI |
| constants, manifests, JSON utils, structured headers, updates interface, EAS client | runtime dependencies | internal AARs; EAS client names an installation identifier, no EAS service |
| asset, file-system, font, keep-awake, DOM webview | inherited registry dependencies | mirrored npm Maven AARs; candidates for later capability reduction |
| RN/Expo native `.so` files | runtime | transitive AAR JNI entries, packaged by Android |
| resources / manifest / ProGuard rules | runtime | AAR resource merging and consumer rules |
| public certificate | consumer trust configuration | validated by plugin, literal metadata in APK |
| health patch | runtime, maintainer-owned | compiled into version-pinned expo-updates AAR |
| bootstrap Hermes bytes | runtime | Dootah AAR asset `index.android.bundle` |
| embedded update ID/time | consumer build | Gradle-generated Expo embedded manifest, no Node |
| Node/npm/Metro/React JS sources/codegen/autolinking | SDK build-time only | locked producer, absent from consumer |
| xprem identity, local HTTP, title probe, debug APK signing | test/demo | fresh consumer only |
| splash/icons, JSX, RN screens, Metro server, Expo app config | disappear from consumer | not required |

## Security and native health

Expo retains signature verification, advertised asset SHA-256 checks, update
selection, durable cache, recovery and signed rollback. No custom OTA protocol,
client DB writes, unsigned manifest allowance or synthetic RN render marker is
introduced. Only public X.509 trust material belongs in the APK. Publisher tokens,
private signing keys and server master keys remain outside SDK artifacts.

The native frame path stays: validated message -> StateFlow -> actual Compose
hardware frame commit -> current-value/current-launch validation -> Expo's existing
successful-launch delegate. Consumers never patch Expo. The existing three-file
MIT patch is pinned to expo-updates 57.0.23 and applied by the producer's locked
`patch-package` installation. `tests/health/NativeHealthTest.kt` runs against that
actual patched module: missing/stale IDs, emergency launches, pre-monitoring calls
and duplicate acknowledgements are covered. The ten-second recovery window remains.

The Gradle plugin's regression tests cover insecure origins/private trust material
rejection and distinct embedded identities after configuration changes. Generation inputs
include consumer package/version, URL, app/channel/runtime, certificate/key ID and
SDK version. Each regeneration receives a fresh UUID, while up-to-date builds retain
the generated manifest, matching Expo's embedded identity lifecycle. This avoids
reusing a failed baseline identity or a stale scope after rebuilding.

## Limits

This local SDK supports API 29+ and arm64-v8a. API 29 is required for the hardware
frame-commit acknowledgement used by this proof. Other ABIs are not advertised;
the plugin rejects unsupported explicit ABI filters. Artifacts are local and not
a public Maven release. Consumer configuration cache and the full AGP compatibility
matrix are not acceptance claims.

The bridge remains the Phase 1 validated title-message contract, not a Portable IR
implementation. RN/Expo still expose broad inherited modules: hiding infrastructure
is not a security sandbox. The later capability boundary remains necessary. The
Dootah Application/Activity base classes are the supported lifecycle integration;
existing custom base-class composition is not yet packaged. The patch requires
review on Expo upgrades. Phase 2C's corrupted HTTP-cache availability risk remains.

## Physical proof and measurements

Recovery inspection found that the interrupted run had installed its initial APK
at 10:02:57 IST, SHA-256
`342ae76ad019529d4dca248de4c92b9144e4ca5e8cebd7d7b742c6e95f3e70c1`.
Its saved UI showed a crash dialog; it was **not** an accepted baseline. The runtime
AAR copied an assumed bundle output directory and silently omitted Hermes bytes.
The correction consumes the RN bundle task's declared `jsBundleDir` and fails the
producer build if `index.android.bundle` is absent. This is an SDK packaging fix;
it adds no consumer tooling. The original isolated 49-task build evidence is
preserved, but compilation alone did not establish a working runtime.

The first correction installed at 14:13:06 IST (SHA-256
`faa5751bd6332f9b9b89beafc34c781408942ba0f451cbcce6021c7301f92aa1`)
then exposed missing RN core-module registration (`PlatformConstants`). It too is
**FAIL**, with no OTA published. RN normally generates `libappmodules.so` in its
application producer. The runtime now includes that existing native registration
output, with an explicit missing-file build failure. Consumers neither compile C++
nor run RN autolinking. Native copying runs at JNI merge, avoiding RN's root-plugin
preBuild dependency cycle. Neither correction bypasses recovery or signing.

The final isolated build executed all **49 tasks** in 59 seconds, with build cache
disabled and `node`, `npm`, `npx`, `yarn`, `pnpm` and `metro` replaced by failing
executables on PATH. Their invocation log remained absent. The isolated directory
contains only the consumer Gradle/Kotlin/Android sources and public certificate;
its sole repository-specific reference is the already-published Maven repository.
No producer source or npm tree is a Gradle input. Reproduce with a new directory:
copy `v2/native-consumer` excluding `build`, `.gradle`, `.kotlin` and
`local.properties`; change only its Maven repository URL to the published SDK
repository; put failing wrappers for those six commands first on PATH (followed
only by standard system binaries), set JDK 21 / ANDROID_HOME, then run
`./gradlew --no-daemon --no-build-cache assembleRelease`. Verify all tasks execute
and the wrappers record no invocations. Android/Maven dependency caches may be
reused; this proof does not claim an empty dependency cache or offline dependency
resolution. The earlier two isolated-build
records remain available alongside this final proof.

The plugin tests passed (2 tests), and the existing actual-patch native health
regressions passed (2 tests); the health patch did not change during recovery.
A separate isolated consumer with `minifyEnabled true` and Android's standard
optimized ProGuard file also built successfully (47 tasks). It uses the same
runtime classes and dependency graph; this shrinker build preceded the native
registration packaging correction and was not installed. The physical acceptance
APK is unminified. A minified physical-device matrix remains a later release gate.
No consumer-written RN/Expo ProGuard exceptions were needed. Upstream AAR rules
and Dootah's reflected module registry keep rule merge through normal AGP behavior.

Final baseline: package `dev.dootah.consumer`, versionCode **1**, versionName
**0.3.0**, runtime `dootah-v2-spike-1`, installed **2026-09-27 14:17:52 IST**.
APK SHA-256:
`5517b94864b56af9043d996876c695f630b86aa3ef592ee877c5c6e9861b3453`.
Embedded UUID `37e0a349-640a-4168-89dd-9bf7874ac6b1`; native title
`Dootah Native Baseline`. A pulled installed APK matches the build hash.
The actual merged manifest contains literal URL/runtime/identity headers and the
pinned public certificate. Unsigned manifests remain disallowed.

Prepared OTA `Dootah packaged SDK OTA` was reused from Dootah's internal export
workspace, not rebuilt in the consumer. Published through unchanged
`v2/server/publish.mjs`: integer ID `17904989088682`, UUID
`9462b741-4897-2007-d0de-854e391eb34a`, 1,433,590 bytes, SHA-256 base64url
`uiVWQcpcet0lhG62oO5bDhuOkZcN78N3MY2FYjyMHo4`.
Host verification checked the exact manifest signature and fetched asset digest;
Android logged successful manifest signature verification, successful asset load
and DownloadComplete. Expo's unchanged download path validates SHA-256 before
promoting the asset. Phase 2C's rejection probes remain applicable to this same
version-pinned signing/hash implementation; they were not repeated here.

**All eight device stages passed:** baseline, OTA download while baseline remains
active, OTA activation, separate online restart, offline cached restart, rollback
delivery, rollback activation and another rollback restart. Each capture asserted
native UI text, Hermes update ID/value, native hardware frame acceptance, Expo's
renderer acknowledgement, unchanged install timestamp and absence of fatal
exceptions. Offline testing blocked only the consumer package and removed ADB
reverse; a failed network check was logged while cached native UI remained healthy.
Networking was restored before rollback.

The existing authenticated xprem rollback path created ID `17904991514312`,
signed `rollBackToEmbedded`, commit time `2026-09-27T08:52:31.432Z`.
The host independently verified its signature; Android applied it through Expo's
unchanged signed directive path. The host verification helper initially omitted
the required embedded-ID header and received 400 after the successful rollback
POST. Supplying that header verified the same directive; no second rollback was
created. Rollback restored the original embedded UUID and title.

No APK replacement, data clear, client DB mutation, signing bypass, synthetic RN
render marker or React surface occurred after the accepted baseline at 14:17:52.
Final on-device SHA-256 and install timestamp match the baseline. Neither failed
baseline attempt is counted as accepted. No EAS service or account was used.

Durable [acceptance record](evidence/phase3-20260927/acceptance.json) includes
identities, selected events, measurements and disclosed failures. Screenshots:
[baseline](evidence/phase3-20260927/final-baseline.png),
[OTA](evidence/phase3-20260927/ota-active.png),
[offline OTA](evidence/phase3-20260927/ota-offline.png),
[rollback](evidence/phase3-20260927/rollback-active.png),
[rollback restart](evidence/phase3-20260927/rollback-restart.png).
Raw logs, producer artifacts, isolated copies and publisher receipts remain outside
Git under `/tmp/dootah-v2-phase3`. No publisher API key or private PEM appears in
the APK; reusable Dootah AARs contain no sample UUID or endpoint.

Consumer requires RN knowledge/source, App.tsx/JSX, package.json/node_modules,
Node/npm, Metro, Expo project/config or manual Expo patches: **NO** for every item.
Consumer source imports only Android/Kotlin, Compose and Dootah APIs.

Cleanup restores package networking, disables the temporary deny chain, removes
ADB reverse and returns the screen timeout to five minutes. The reused
`dootah-v2-server-phase2c` container is stopped; PostgreSQL and persistent
app/assets/rollback state are preserved. Restart the existing container for the
next phase. No unrelated V1/compiler matrices were rerun.

### Packaging measurements

| Item | Size |
| --- | ---: |
| Fresh unminified arm64 consumer APK | 56,711,688 bytes (54.08 MiB) |
| Dootah runtime AAR, including bootstraps | 537,748 bytes |
| Runtime plus eleven Dootah internal Expo AARs | 4,283,919 bytes |
| Hermes embedded bootstrap, uncompressed | 1,149,808 bytes |
| Packaged native libraries, uncompressed | 13.78 MiB, 14 libraries |
| DEX, uncompressed | 38,275,724 bytes, 4 files |

Major native entries: React Native 6,985,168 bytes, Hermes VM 2,477,320 bytes,
Expo modules core 1,448,344 bytes and shared C++ runtime 1,292,896 bytes.
`libappmodules.so`, Hermes, Expo JNI and all other library paths occur exactly once;
only arm64-v8a is packaged. Duplicate shared C++ inputs resolve through the plugin.
This is an absolute sample footprint, not a measured incremental delta over an
otherwise identical native app. No size optimization is claimed.

Published coordinates include `dev.dootah:runtime-v2`, the `dev.dootah` plugin
marker/implementation, and `dev.dootah.internal:{expo,expo-brownfield,
expo-constants,expo-eas-client,expo-json-utils,expo-log-box,expo-manifests,
expo-modules-core,expo-structured-headers,expo-updates,expo-updates-interface}`
at `0.3.0-local`, plus the five mirrored upstream npm Maven modules listed above.
RN and Hermes resolve transitively from Maven; consumers do not select or resolve
their native dependencies manually. POM and Gradle module metadata carry the
resolved versions. Artifacts and local caches remain outside Git.


## Continuation

Phase 4 work and its acceptance status are recorded in
[Compose instrumentation](COMPOSE_INSTRUMENTATION.md). The historical Phase 3
measurements above describe the unminified `0.3.0-local` proof, not the current
consumer. Publishing analysis/transpilation remains outside normal Android compilation.


## Phase 6 runtime replacement (SDK 0.6.0-local)

The Phase 3 record above remains historical. Phase 6 replaces the shared ReactHost
with a native Application plus Expo's native update controller and AndroidX
JavaScriptEngine 1.0.0. No ReactApplication, module registry or JS bridge is created.
The consumer remains normal Kotlin/Compose with the packaged Gradle plugin and AAR;
no consumer Expo project, npm tree, compiler plugin or FIR/IR mutation is required.
The installed capability asset declares runtime/dispatch ABI 2 and Logic ABI 1.
This native change requires a new baseline APK, runtime `dootah-v2-logic-2`.

Signed remote launch assets contain bounded Portable IR JSON. The native validator
regenerates executable JS, passes only portable values to a fresh isolated-process
isolate, and renders the typed result through BasicText. Required sandbox features
are checked; unavailable providers and validation/execution errors retain native
behavior. See [security](SECURITY_MODEL.md), [capabilities](CAPABILITIES.md), and
[logic bounds](PORTABLE_LOGIC.md) for the current contract and physical acceptance.


## Phase 7 native telemetry integration

SDK/plugin `0.7.0-local` adds trusted native Cloud telemetry and scoped enrollment.
The user explicitly authorized the required new acceptance baseline; ABI 2, Logic
ABI 1, dispatch, native renderer, validator, sandbox and signed delivery stay frozen.
See [Cloud architecture](CLOUD_ARCHITECTURE.md) and [telemetry](TELEMETRY.md).
No publisher credential is packaged in the APK and no networking capability is
exposed to portable code. The previous phase records above remain historical.


## Phase 8 SDK patches (0.7.4-local)

Real-app acceptance ([REAL_APP_ACCEPTANCE.md](REAL_APP_ACCEPTANCE.md)) produced four generic
patches; Runtime ABI 2 and Logic ABI 1 are unchanged. Current coordinates are plugin and
`dev.dootah:runtime-v2` **0.7.4-local** (internal Expo AARs unchanged at 0.6.0-local).

- **No Activity base class is required.** `DootahApplication` installs the embedded
  frame acknowledgement for every Activity through `ActivityLifecycleCallbacks`, so
  `ComponentActivity`/platform themes and existing AppCompat base classes work unchanged.
  `DootahActivity` remains an optional empty compatibility base. Integration is: the
  plugin, the `dootah {}` block, `minSdk 29`, and `DootahApplication` as the Application
  superclass (Hilt `@HiltAndroidApp` subclasses work).
- **Manifest surface:** the runtime removes inherited, never-loaded Expo/RN storage and
  overlay permissions and `FileSystemFileProvider`; consumers gain only `INTERNET`.
  App-declared permissions are preserved.
- **Classpath:** Guava 31.1-android is exposed as API so apps compiling against
  `ListenableFuture` (e.g. WorkManager users) keep consistent compile/runtime classpaths.
- **ABI:** non-arm64 ABI split outputs are rejected like non-arm64 `abiFilters`.
- **Measured toolchain floor:** the pinned dependency graph requires **AGP ≥ 8.9.1 and
  compileSdk ≥ 36**; validated with AGP 8.13–9.4.1, Gradle 8.13–9.7.1, Kotlin 2.2.0–2.4.20,
  isolated projects and configuration cache. AGP 8.7.2 (Tivi) is not supported.
- Artifact POMs declare no license yet; license metadata needs a product decision.
- The installed renderer uses unthemed `BasicText` defaults; OTA text is low-contrast in
  dark-themed apps (observed on Mihon and Read You).

## Post-E2E maintenance (0.7.5-local)

Generic fixes after the clean-slate EasyNotes E2E validation; Runtime ABI 2, Logic ABI 1,
dispatch, renderer, sandbox and signed delivery are unchanged.

- **One SDK version source.** `v2/android-sdk/version.properties` sets the plugin and
  `runtime-v2` version; the plugin adds the runtime of its own version and the native
  consumer fixture reads the same file.
- **Hook report.** Every instrumented variant writes
  `build/outputs/dootah/<variant>/hooks.json` (count, owner, method, descriptor and
  `dth1:` identity of each entry point found in the instrumented project classes) during
  `assemble`/`bundle`/`install`. Zero entry points is a build **warning**, because
  `dootah import` will refuse such a build. Set `dootah { requireHooks = true }` in
  acceptance or CI builds to make zero a build failure.
- **Required tests.** `build-sdk.sh` now runs the runtime unit tests and the
  fixture-backed instrumentation gate (`tests/compose-hook.sh`), and fails if any
  required suite is missing, empty or skipped.
- **`verification_failed`** is classified from the updater's structured code-signing log
  entry instead of event text; see [telemetry](TELEMETRY.md).
