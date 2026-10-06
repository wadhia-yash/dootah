> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# Phase 8 real-app acceptance matrix (frozen)

Frozen on 2026-09-29, **before** any Phase 8 integration, build or implementation
change. The result record is [REAL_APP_ACCEPTANCE.md](REAL_APP_ACCEPTANCE.md).
This file must not be edited to fit results; later corrections are appended in the
amendment log at the end with their reason.

## Frozen Dootah baseline under test

| Item | Frozen value |
| --- | --- |
| Starting commit | `764b3c1` on `pivot/dootah-v2` |
| SDK / plugin | `0.7.0-local` |
| Runtime / dispatch ABI | 2 |
| Logic ABI | 1 |
| Capabilities | exactly `logic.pure.v1`, `compose.basicText.v1` |
| Portable grammar | Phase 6 `LogicLowering` subset, unchanged |
| Portable execution | AndroidX JavaScriptSandbox, `RestrictedSandbox`, unchanged |
| Identity | Phase 4 `FunctionIdentity` (`dth1:`), unchanged |
| Cloud | Phase 7 release/rollout/rollback model, unchanged |
| Delivery | private xprem MIT composition behind the Cloud gateway, no EAS |

Only a demonstrated generic defect in already-claimed behavior may change code.
Every such change is recorded in the acceptance record with a regression test and
reruns of the affected apps. Grammar, capabilities, ABIs, sandbox boundary, identity
algorithm and Cloud release model are **not** changed for any acceptance app.

## Applications and exact upstream revisions

Fresh clones at these exact commits (verified to exist upstream). The pre-existing
V1 corpus checkouts, which contain uncommitted V1 edits, are not used or modified.

| App | Upstream | Commit (frozen) | Why selected |
| --- | --- | --- | --- |
| Mihon | github.com/mihonapp/mihon | `449c4e08e76deb06ac9ec520efafe3f8d6e181ec` | Required; largest app, newest toolchain, exposed V1 compiler edge cases |
| Now in Android (NiA) | github.com/android/nowinandroid | `12f80da6518e161ed16a06a68e71fb8a873576d6` | Google reference architecture: convention plugins, Hilt, flavors, feature modules, project isolation |
| JetNews | github.com/android/compose-samples (`JetNews/`) | `4c1fe7586e2fbf1c934925ef8ab64d3803361423` | Small single-module Compose app; compileSdk 37 |
| Read You | github.com/ReadYouApp/ReadYou | `d2b979ccad9a3e54b9499929a9dc2824578b0fd9` | Medium single-module app, AGP 8 / Gradle 8 line, flavors, Hilt/Room |
| Tivi | github.com/chrisbanes/tivi | `a0c62c2c763c83e3a0ecf79b283224374bb06c4a` | Oldest toolchain (Kotlin 2.0.21, AGP 8.7.2), KMP / Compose Multiplatform shape |

Tivi is deliberately included as a different project shape. Its upstream was
archived as deprecated at that commit; an upstream build failure is recorded as
`UPSTREAM_APP_BUILD_ISSUE`, not a Dootah failure.

## Declared toolchains (from upstream files; confirmed or corrected by builds)

| App | Gradle | AGP | Kotlin / Compose compiler | Compose | DSL | minSdk | JDK used |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Mihon | 9.7.1 | 9.4.1 | 2.4.20 | BOM 2026.09.00 (alpha BOM) | Kotlin DSL + included build-logic | 26 | 21 |
| NiA | 9.7.1 | 9.3.2 | 2.3.0 | BOM 2025.09.01 | Kotlin DSL + build-logic convention plugins | 23 | 21 |
| JetNews | 9.5.0 | 9.3.1 | 2.3.20 | BOM 2026.08.00 | Kotlin DSL, version catalog | 26 | 21 |
| Read You | 8.13 | 8.13.0 | 2.2.0 | BOM 2025.10.01 | Kotlin DSL | 26 | 21 |
| Tivi | 8.11 | 8.7.2 | 2.0.21 | Compose Multiplatform 1.7.0 | Kotlin DSL + build-logic, KMP | 24 | 21 |

No selected app uses Groovy build scripts; the Dootah plugin itself was validated
from the Groovy `v2/native-consumer`. Kotlin DSL versus Groovy is therefore **not**
a varied dimension here and is not claimed.

## Project shape variation

| App | Modules (Gradle includes) | `@Composable` in app module / elsewhere | Release shrinking | Variants / flavors | DI / navigation / state |
| --- | --- | --- | --- | --- | --- |
| Mihon | 17 | ~490 / ~83 | R8 + resource shrinking; release signed with debug key | debug, release, foss, nightly, benchmark; ABI splits enabled | Metro DI; Voyager-style screens; StateFlow/ScreenModel |
| NiA | 35 | ~4 / ~154 | R8 when `minifyWithR8`; debug key | demo/prod × debug/release; baseline profiles | Hilt; Navigation Compose; ViewModel/StateFlow |
| JetNews | 1 | ~94 / 0 | R8; debug keystore | debug/release | manual container; adaptive navigation; ViewModel |
| Read You | 1 | ~278 / 0 | R8 + resource shrinking; release keystore properties | github/fdroid/googlePlay × debug/release | Hilt; Navigation Compose; Room/Paging |
| Tivi | 6 top-level (KMP subprojects) | 0 / ~168 | R8 | debug/release/qa | kotlin-inject; Circuit; KMP shared UI |

Counts are a syntactic census (tests excluded) for context only; they are not
analyzer results. Member, private, extension, override, generic and file-annotated
composables all occur in the corpus.

## Pre-registered hypotheses (written before integration)

Recorded so that later findings cannot be redefined after the fact.

1. **Kotlin metadata version.** The Phase 4 visitor reads metadata with
   `kotlin-metadata-jvm` 2.1.21 `readStrict`, which accepts metadata up to one
   version newer. Kotlin 2.3/2.4 classes (Mihon, NiA, JetNews) may be silently left
   uninstrumented. Silent zero-instrumentation would be a generic toolchain defect.
2. **Publisher parser version.** Import/analysis parse every source file with pinned
   Kotlin 2.1.20 PSI and refuse on any syntax error. Newer syntax anywhere in an app
   could refuse the entire app, including unrelated supported functions.
3. **minSdk 29 and arm64-only.** The runtime AAR declares minSdk 29; the plugin adds
   an `arm64-v8a` NDK filter and rejects other filters. Apps must raise minSdk.
   Mihon's enabled ABI splits may conflict with the NDK filter.
4. **Lifecycle base classes.** `DootahActivity` extends `AppCompatActivity`, which
   requires an AppCompat theme. NiA and JetNews use `ComponentActivity` with platform
   `android:Theme.Material*` themes; integration may require restructuring.
5. **Library-module composables.** Instrumentation uses `InstrumentationScope.PROJECT`
   in the application module only. NiA and Tivi have almost no app-module composables.
   This is documented Phase 4 scope, expected `UNSUPPORTED_BY_DESIGN`, not a bug.
6. **Telemetry enrollment.** Phase 7 requires integrator code to call
   `DootahApplication.enrollTelemetry(ticket)`. Real apps need a small enrollment
   integration; it must be identical and generic across apps and is counted as cost.
7. **Natural OTA targets.** The grammar renders only `BasicText(String)`. Real
   baseline bodies are expected to use Material `Text`/layouts, so the number of
   functions whose *unchanged baseline body* is already in the subset is expected
   to be near zero. This is reported as coverage, separately from correctness.
8. **Dependency graph.** The runtime exposes RN 0.86.3, AppCompat 1.7.0 and Compose
   1.7.8 APIs; Gradle conflict resolution with newer app versions may upgrade them.

## Pass categories (reported separately per app)

| Code | Category | PASS requires |
| --- | --- | --- |
| A | Installation | Plugin/config/certificate/app identifiers plus lifecycle wiring only; no app restructuring, theme change or screen rewrite |
| B | Build | Upstream-native and Dootah-integrated `assembleDebug` (D8) and a release/R8 build where upstream supports it; configuration cache where upstream enables it |
| C | Instrumentation | Eligible app-module composables hooked; ineligible forms and generated helpers untouched; original bodies/descriptors preserved; overload isolation |
| D | Analyzer | Import agreement between source identity and installed hook constant; supported edits lower to deterministic IR; unsupported edits refuse the whole publication |
| E | OTA | A real Kotlin source edit to an existing installed function becomes a Cloud release via `dootah publish` |
| F | Runtime | The release executes through JavaScriptSandbox and renders through native Compose with native frame health on device |
| G | Cloud | Publish, rollout, signed delivery, registration, telemetry, metrics and rollback through the Phase 7 Cloud path |
| H | Fallback | Offline/Cloud-down cached behavior, refused source, rejected artifacts and rollback preserve native behavior |

Each category is PASS, PARTIAL, FAIL, NOT_APPLICABLE or NOT_ATTEMPTED with a reason.
An app is **fully accepted** only when A–H all pass. Direct xprem publication may be
used for debugging only and never counts toward E or G.

## OTA target selection rule

Targets are chosen from functions that the import step proves are installed hooks
(source identity equals the hook constant and the ID survives in the final DEX).
Preference order: String/Int/Boolean parameters that exercise portable inputs;
visible on the app's first screens without accounts or network content; an edit
a developer could plausibly make. The edit is a normal Kotlin body change within
the frozen grammar. Because the grammar renders `BasicText`, an edit that replaces
a Material text body with `BasicText` changes typography and is reported as such.
No annotation, wrapper, manual dispatch, handwritten JS or manual function ID.
The number of targets whose baseline body is already in the subset is reported.

## Device plan

Only one physical device is attached: Samsung **SM-E426B**, Android 13 (API 33),
arm64-v8a, connected over wireless ADB. No additional physical device diversity is
claimed; emulator results, if any, are labelled as emulator results.

Per physically tested app: accepted APK SHA-256, install timestamp, package,
SDK/runtime/ABI versions, native baseline, OTA activation, online restart, offline
restart (package networking denied and ADB reverse removed), signed rollback and
final native state. No reinstall or data clear of that package after its accepted
baseline. Existing packages from earlier phases are not uninstalled.

## Cloud, rollout and failure plan

Each accepted app gets its own Cloud organization → app → environment, with its
own isolated xprem upstream app, signing certificate, runtime version and registered
contract. Rollout 0% → partial → 100% → pause → resume is exercised on Mihon with
synthetic enrolled installations for cohort arithmetic plus the phone for the policy
it actually falls into. Failure probes:

| Probe | Where |
| --- | --- |
| Cloud unavailable, cached OTA offline, unsupported source refusal | Every accepted app |
| xprem unavailable, invalid signature (tampering relay) | One accepted app |
| Unknown function, wrong runtime ABI, wrong Logic ABI, invalid capability, malformed artifact | Cloud validation against real-app environments; device-level rejection reuses Phase 6 evidence for the unchanged runtime |
| Sandbox unavailable / timeout / resource rejection | Phase 6 common architecture tests, not repeated per app |

## Minimum success bar (from the Phase 8 brief)

At least four unrelated apps attempted (five planned); at least three substantial
apps fully accepted **if** their source naturally contains supported forms; Mihon
included; two toolchain/project shapes; two R8 proofs; one Cloud physical OTA per
accepted app; zero app-specific production hacks. Fewer accepted apps caused by the
frozen grammar are reported, not fixed by grammar expansion.

## Amendment log

The frozen sections above are unchanged since the freeze (SHA-256 of the frozen file
recorded in the Phase 8 evidence). Amendments below were appended after results.

1. **SDK patch versions.** Five generic defects (below and in the acceptance record)
   required SDK patch releases `0.7.1`–`0.7.4-local`. Runtime ABI 2, Logic ABI 1,
   capabilities, grammar, sandbox, identity algorithm and Cloud model are unchanged.
   JetNews was accepted on `0.7.1-local`, Mihon on `0.7.2-local`, Read You, NiA and Tivi
   on `0.7.4-local`; JetNews and Mihon were rebuilt with `0.7.4-local` and compared.
2. **JetNews device variant.** The native upstream JetNews release/R8 APK crashes at
   startup (`WorkDatabase` instantiation under R8) before any Dootah integration, so
   JetNews device acceptance uses its debug/D8 variant (`UPSTREAM_APP_BUILD_ISSUE`).
3. **Mihon ABI splits.** Hypothesis 3 was confirmed as a silent defect rather than a
   configuration error; the integration restricts Mihon's split list to `arm64-v8a`.
4. **Hypotheses 2 and 8.** The pinned PSI parser accepted every source file of all
   five apps (hypothesis 2 not confirmed). Hypothesis 8 materialized as two concrete
   defects: injected permissions/provider and a runtime-only Guava classpath.
