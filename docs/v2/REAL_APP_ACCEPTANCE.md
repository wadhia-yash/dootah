> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# Phase 8 real-app acceptance

2026-09-29. Frozen plan: [REAL_APP_MATRIX.md](REAL_APP_MATRIX.md) (freeze digest
`deb55169…`, reverified after the appended amendment log). Curated evidence:
[evidence/phase8-20260929/](evidence/phase8-20260929/). Raw logs, APKs, retained class
directories, Cloud credentials and clones stay outside Git under `/tmp/dootah-v2-phase8`
and `~/.dootah-v2/phase8`. Screenshots are not committed because the device status bar shows
personal notification icons; each stage's visible text is recorded in the per-app JSON.

**Result: Phase 8 PASS with disclosed limits.** Five unrelated apps attempted; three
fully accepted with physical Cloud OTA (Mihon and Read You on R8 release builds, JetNews on
its debug build because its upstream R8 build crashes natively); Now in Android partially
accepted (installation, builds, instrumentation, refusal, embedded runtime/telemetry; no
OTA target by design); Tivi not integrable on its toolchain. Five generic Dootah defects
were found by real apps and fixed generically with regressions (four SDK patch releases);
zero app-specific production code. OTA text uses the frozen unthemed `BasicText` renderer
and is low-contrast in dark-themed apps (risk 1). Only one physical device (Samsung SM-E426B, Android 13) was available.

| App | Upstream SHA | Toolchain | Native build | Dootah build | Hook | Analyzer | OTA | Offline | Rollback | Cloud/Telemetry | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Mihon | `449c4e08` | Gradle 9.7.1, AGP 9.4.1, Kotlin 2.4.20 | PASS D8+R8, CC | PASS D8+R8, CC | 100 methods / 86 classes, 0 violations | 15 agreements, 423 fns | PASS R8 | PASS | PASS | PASS incl. 0/40/50/100%, pause, resume | splits limited to arm64 |
| Read You | `d2b979cc` | Gradle 8.13, AGP 8.13.0, Kotlin 2.2.0 | PASS D8+R8 | PASS D8+R8 | 26 methods, 0 violations (+Hilt) | 3 agreements, 288 fns | PASS R8, 3 inputs | PASS | PASS | PASS | tampered manifest rejected |
| JetNews | `4c1fe758` | Gradle 9.5.0, AGP 9.3.1, Kotlin 2.3.20 | PASS D8; R8 builds but crashes natively | PASS D8+R8 | 23 methods, 0 violations | 3 agreements, 94 fns | PASS D8 | PASS | PASS | PASS | upstream R8 crash |
| Now in Android | `12f80da6` | Gradle 9.7.1, AGP 9.3.2, Kotlin 2.3.0, isolated projects | PASS D8+R8, CC | PASS D8+R8, CC | 0 eligible; Dootah byte-transparent | import refuses (0 identities) | N/A by design | PASS (embedded) | N/A | enrollment + embedded events | UI in 35-module graph |
| Tivi | `a0c62c2c` | Gradle 8.11, AGP 8.7.2, Kotlin 2.0.21, KMP | PASS D8+R8 | FAIL (AGP/compileSdk floor, license metadata) | not reached | N/A | N/A | N/A | N/A | N/A | 0 app-module composables |

CC = configuration cache stored. Hook counts are methods, from the bytecode verifier.

## Frozen surfaces

Runtime/dispatch ABI **2**, Logic ABI **1**, the two capabilities, the Phase 6 portable
grammar (`LogicLowering`/`PortableProgram`), `RestrictedSandbox`/AndroidX
JavaScriptSandbox, the Phase 4 `FunctionIdentity` algorithm (golden test unchanged),
the Expo health patch and the Phase 7 Cloud release model were **not changed**. No
compiler plugin, FIR/IR mutation or compiler-version bridge was added. Every Dootah
code change is one of the five generic defect fixes below.

## Generic Dootah defects found and fixed

| # | Defect (classification) | Evidence | Generic fix | Regression |
| --- | --- | --- | --- | --- |
| 1 | Kotlin ≥ 2.3 metadata unreadable → **every class silently left native** (DOOTAH_GENERIC_BUG / toolchain) | Kotlin 2.3.20 and 2.4.20 write metadata 2.4.0; `kotlin-metadata-jvm` 2.1.21 `readStrict` accepts ≤ 2.2.0. Frozen 0.7.0 integration of JetNews: 0 hooks in 195 classes, no warning | Read metadata with `kotlin-metadata-jvm` **2.4.20** (reads ≤ 2.5.0, still strict); warn once when metadata is unreadable instead of silently skipping | Real Phase 4 fixture rewritten to metadata 2.3/2.4/2.5 yields identical IDs; 2.6 stays native |
| 2 | `DootahActivity` required an **AppCompat theme** (DOOTAH_GENERIC_BUG / hidden sample assumption) | JetNews (platform-themed `ComponentActivity`) on 0.7.0: `IllegalStateException: You need to use a Theme.AppCompat theme` at launch | Embedded frame acknowledgement registered by `DootahApplication` through `ActivityLifecycleCallbacks` for any Activity (`peekDecorView`, attach-state listener); `DootahActivity` kept as an empty compatibility base | Bytecode test: `onCreate` registers callbacks, observer never calls `getDecorView`, base holds no frame logic; device: JetNews/NiA/Mihon/Read You embedded health |
| 3 | ABI splits bypass the arm64 guard → **mislabelled non-arm64 APKs** without Dootah natives (DOOTAH_GENERIC_BUG) | Mihon split outputs `armeabi-v7a`/`x86`/`x86_64` contained RN libraries but not `libappmodules`, `libexpo-modules-core`, `libexpo-updates` | Plugin rejects non-arm64 ABI split outputs via the public variant-output filter API, consistent with its existing `abiFilters` rejection | `DootahPluginTest.rejectsNonArm64AbiSplitOutputs`; Mihon build fails with an actionable message until splits are arm64-only |
| 4a | SDK **injected permissions and a provider** into host apps (DOOTAH_GENERIC_BUG) | JetNews/Mihon gained `READ/WRITE_EXTERNAL_STORAGE` (≤ API 32) and `expo.modules.filesystem.FileSystemFileProvider`; debug builds also `SYSTEM_ALERT_WINDOW`. Read You's own `WRITE_EXTERNAL_STORAGE maxSdkVersion=28` failed manifest merge | Runtime AAR manifest removes these inherited, never-loaded entries (`tools:node="remove"`, applies to lower-priority transitive libraries only) | `ManifestSurfaceTest`; producer gate asserts the published runtime requests only `INTERNET`; Read You and NiA permission sets identical to native builds, Read You's own permission preserved |
| 4b | Runtime-only Guava → consumer **compile classpath loses `ListenableFuture`** (DOOTAH_GENERIC_BUG) | Read You (WorkManager): `Cannot access class 'ListenableFuture'`; javascriptengine's Guava replaced `listenablefuture:1.0` by the empty artifact and AGP carried it to the compile classpath | Runtime exposes the same Guava 31.1-android as `api`, keeping compile and runtime classpaths consistent | Producer gate on the published Gradle module metadata; fails on 0.7.3, passes on 0.7.4 |

Patch releases: `0.7.1-local` (1, 2), `0.7.2-local` (3), `0.7.3-local` (4a), `0.7.4-local`
(4b). Runtime ABI 2 and Logic ABI 1 are identical in every patch.

## Deferred generic issue (not fixed in Phase 8)

**Resolved in SDK `0.7.5-local`** (post-E2E maintenance, see [telemetry](TELEMETRY.md)):
classification now uses the updater's structured `UpdateCodeSigningError` log entry, with
no change to the pinned Expo event surface. The original Phase 8 finding follows.


`verification_failed` telemetry never fires for real code-signing rejections. Expo's
state-change event exposes only `Failed to download remote update`; the SDK's text
classifier looks for “signature”. Rejection itself works (Mihon directive and Read You
manifest tamper probes). Fixing classification needs a change to the version-pinned
Expo event surface, outside Phase 8's minimal-fix scope. Phase 7 documented the
classifier as best-effort; Phase 8 shows it is effectively non-functional. Open for Phase 9.

## Per-app results

All device stages below were captured on one install per app; the APK SHA-256 and
install timestamp are identical in every capture (curated in the per-app evidence JSON).

### Mihon (fully accepted, R8)

Integration: settings repository, plugin + `dootah {}` block, `minSdk 29`, arm64-only split
list, `App : DootahApplication` (qualified `super<>` call) plus the shared enrollment
snippet. `BaseActivity` stays `AppCompatActivity`. Frozen 0.7.0 would instrument nothing
(defect 1); ABI splits exposed defect 3. Accepted `app-arm64-v8a-release` (R8), SHA
`f484340e…7535`, installed 2026-09-29 20:59:00 IST, SDK `0.7.2-local`.

- Bytecode: 4,024 classes, 25,755 methods, 100 hooked (60 members: 56 `Content()` screen implementations plus 4 others;
  static 0/1/2/4-argument forms), 0 in lambdas/helpers, original bodies exact suffixes,
  no prefix stores, distinct overload IDs, 0 violations.
- Analyzer: all 616 files parse; 423 composables; 15 source↔installed agreements, all in
  the R8 DEX. `UnreadBadge(Long)` has an analyzer ID but no hook (Long unsupported) — both
  sides agree it stays native. Coverage reasons: 234 object/lambda parameters, 49
  generic/override, 42 extra annotations, 31 parameter forms, 20 member forms, 16 aliases.
- OTA: `PreferenceGroupHeader(title)` → `if (title == "Theme") "$title (updated over the air)"`
  rendered in Settings › Appearance through the sandbox; other headers pass the real
  input through. Typography and colour change to BasicText defaults (black text), which is
  low-contrast on Mihon's dark theme (see risk 1).
- Rollout: phone bucket 4158; 0% no offer, 40% excluded, 50% downloaded (signature verified),
  100% active, pause (0/20 synthetic offers, phone keeps cached release), resume; 20
  synthetic installations matched the documented hash with 0 mismatches at every step.
- Failure: xprem down, Cloud down and offline restarts keep cached OTA; tampered signed
  `noUpdateAvailable` directive rejected; valid `InfoWidget` edit using `uppercase()`
  refused the whole publication; 11 adversarial artifacts refused by Cloud (422, 0 releases).
- Rollback: signed `rollBackToEmbedded` (commit 15:40:11Z, host-verified with Mihon's key),
  native header restored, healthy online and offline. 42 device events incl. all seven types.
- Rebuilt on 0.7.4: pre-R8 instrumented classes byte-identical (4,024), same 75 DEX hook
  IDs, identical native libraries; R8 output differs only because the removed provider
  is no longer kept.

### Read You (fully accepted, R8)

Integration: repositories, plugin/config, `minSdk 29`, `AndroidApp : DootahApplication`
(Hilt generates `Hilt_AndroidApp` over it), shared snippet. First exposed defects 4a and 4b.
Accepted `githubRelease` (R8), SHA `a9219d28…d182`, installed 21:47:24 IST, SDK `0.7.4-local`.
Permission set identical to the native build; its own `WRITE_EXTERNAL_STORAGE maxSdk 28` kept.

- Bytecode: 26 hooked methods, 0 Dootah violations; Hilt's transform rewrote 6 entry-point
  superclasses (14 methods), attributed and excluded.
- Analyzer: 373 files, 288 composables, 3 agreements.
- OTA: `StickyHeader(dateString, isShowFeedIcon, articleListTonalElevation)` → BasicText using
  all three real inputs; rendered `▸ Wednesday, 3 June 2026` from the app's own feed.
- A relay corrupting the offered manifest signature produced
  `Code signing verification failed for manifest`; nothing downloaded. Clean path then
  downloaded with signature verification. Online, Cloud-down and offline restarts, refused
  `System.currentTimeMillis()` edit, signed rollback (16:24:14Z) and offline rollback pass.
  The app's ToS onboarding was accepted on the test install to reach content.

### JetNews (fully accepted, D8)

Integration: repositories, plugin/config, `minSdk 29`, `JetnewsApplication : DootahApplication`,
snippet, loopback cleartext (test origin). `MainActivity` stays `ComponentActivity` with a
platform theme — the 0.7.0 contract crashed here (defect 2). Upstream release/R8 crashes
natively at `WorkDatabase` creation, so the D8 debug build is the accepted APK: SHA
`3b3e4521…4f34`, installed 20:07:44 IST, SDK `0.7.1-local`.

- 23 hooked methods, 0 violations; 3 agreements; OTA `PostListDivider()` → section label via a
  local constant, rendered at four call sites; IR SHA-256 `b8459521…` identical across runs.
- Sandbox: JavaScriptSandbox in isolated UID `u0_i9295` vs app `u0_a708`; no RN/Hermes/
  registry activity in the app process (same check passes for Mihon and Read You).
- Online, Cloud-down, offline, refused `System.nanoTime()` edit (normal `assembleDebug`
  still passes), signed rollback, offline rollback restart; all seven event types.
- Rebuilt on 0.7.4: DEX differs by 8 bytes (SDK version string and its D8 content hash);
  native libraries and other assets identical; only `INTERNET` added versus native.
- Instrumentation cost (dispatch on vs `composeDispatch = false`): 23 hooks, +3,044
  uncompressed DEX bytes, same APK size.

### Now in Android (partially accepted)

Isolated projects and configuration cache (`problems=fail`) stay green with the plugin.
Accepted `demoRelease` (R8), SHA `59094c20…b18d`, installed 22:27:55 IST, SDK `0.7.4-local`.
Only 4 of ~158 composables live in the app module and all take object/lambda parameters,
so no hook is eligible (`UNSUPPORTED_BY_DESIGN`: library-module composables are not
instrumented). Rerunning the app transform with `composeDispatch = false` produced
byte-identical output for all 122 classes: the 15 changed methods come from NiA's own
Firebase Performance/Hilt transforms. Import refuses (`No installed dispatch identities`).
Native UI, embedded health, online/offline/Cloud-down restarts and enrollment with
embedded `update_checked/activated/healthy` events pass on device. No OTA claim.

### Tivi (not integrable on its toolchain)

Native `qaDebug` and `standardRelease` build. Integration stops at `checkAarMetadata`:
Dootah's `expo-modules-core` requests `androidx.core-ktx 1.17.0`, which needs AGP ≥ 8.9.1 and
compileSdk ≥ 36 (Tivi: 8.7.2 / 34). Its licensee policy also rejects RN/Meta license URLs
(app policy) and Dootah artifacts that declare no license (Dootah gap). Upgrading Tivi's
AGP would restructure the upstream toolchain, so it was not done. Its app module contains no composables.

## Cross-app evidence

- **Identity agreement:** every agreement was checked by import against the actual hook
  constant in retained pre-R8 classes and the final DEX; no disagreement occurred.
- **Unsupported forms fail closed:** four real unsupported edits refused whole
  publications; unreadable metadata stays native (now with a warning); NiA/Tivi-like shapes
  produce no hooks and import refuses.
- **R8:** Mihon, Read You and NiA release builds are R8-minified and installed; hooks,
  renderer and sandbox survive shrinking with no Dootah-specific rules in the apps.
- **Cloud:** each app has its own organization, app, environment, isolated xprem upstream
  app and signing certificate. 11 adversarial artifacts (ABI, Logic ABI, runtime, capability,
  unknown function, parameter types, operation, raw JS, malformed value, root field) were
  refused with 422 and created no release. Cloud suite 13/13 with no skips.
- **Health semantics:** a remote release is reported healthy when the overridden function
  first renders. Mihon's target is off the first screen; health arrived ~34 s after launch,
  and the pinned patch has no first-render deadline, so the launch was not penalized.
- **Telemetry:** real device events for check, available, download start/complete,
  activation, health and rollback for all three accepted apps; metrics use distinct-install
  denominators (Mihon: 21 compatible incl. 20 synthetic, 1 adopted, 100% health).
  Crash-free analytics are not claimed.
- **Cost:** release APK growth +22.2 MB (JetNews), +30.6 MB (Mihon arm64), +30.5 MB (Read You),
  +23.0 MB (NiA), dominated by RN/Hermes/Expo native libraries. Cold starts on accepted
  APKs: NiA 260–277 ms, Read You 363–425 ms, Mihon 806–870 ms, JetNews debug ~1.5 s; no
  native control was installed, so no startup regression figure is claimed. Build-time
  runs were not controlled for up-to-date library modules.
- **Integration cost per app:** 3–4 files, ~28–30 added lines, of which 14 are the shared
  acceptance-only enrollment snippet; plus a public certificate file and, for Mihon, a
  one-line split restriction. The snippet reads a one-use ticket from a launch extra and
  is not a production pattern.
- **Existing suites:** plugin 8/8 (real fixtures), runtime 5/5, native health 3/3,
  publisher 18/18, portable 2/2, Cloud 13/13; producer gates fail on 0.7.3, pass on 0.7.4.

## Remaining risks

1. **Unthemed renderer output (UNSUPPORTED_BY_DESIGN, user-visible).** The frozen
   `compose.basicText.v1` renderer draws foundation `BasicText` defaults: black text, no
   app theme or typography. Screenshots of Mihon (dark theme) and Read You show the OTA
   labels at very low contrast against dark backgrounds. The OTA is functionally correct
   but not visually production-ready for dark-themed apps; a themed text capability would
   be a new installed capability and APK, not a Phase 8 fix.
2. **Coverage is narrow by design.** Natural baseline bodies already inside the subset: 0
   in every app. Real OTA edits replace Material bodies with `BasicText`, losing app
   typography. Library-module composables (NiA, Tivi) are never hooked.
3. **SDK floor and weight.** The pinned Expo/RN graph requires AGP ≥ 8.9.1, compileSdk ≥ 36,
   minSdk 29, arm64 only, and adds 22–31 MB per release APK.
4. **Licensing metadata.** Dootah artifacts publish no license; a product license decision
   is required before compliance-enforcing apps can adopt the SDK.
5. **`verification_failed`** is not emitted for real signature rejections (deferred above).
6. **Enrollment** still requires integrator code; the acceptance snippet is not production-safe.
7. **Single device.** One Samsung Android 13 phone; no API 29–32 or second-vendor evidence.
8. **Local infrastructure.** Loopback Cloud/xprem with ADB reverse and cleartext test origins;
   no hosted HTTPS deployment is claimed. The 204 no-offer response logs as a failed check.

## Exact Phase 9 starting point

Start from the Phase 8 commit, SDK `0.7.4-local`, Runtime ABI 2 / Logic ABI 1. Phase 9
(public README-only install/publish/rollback exercise) should first decide the product
license for published artifacts, document the SDK toolchain floor (AGP ≥ 8.9.1,
compileSdk ≥ 36, minSdk 29, arm64) and production enrollment, and address the
`verification_failed` classification. Phase 9 has not begun.
