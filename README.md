# Dootah

A CodePush-style over-the-air (OTA) update system for native Android/Kotlin apps.

## 1. What Dootah is

Dootah lets a native Android app fetch and run a small **Kotlin/JS bundle** at runtime,
without shipping a new APK/AAB:

- The bundle is written in Kotlin, compiled to JavaScript via Kotlin Multiplatform
  (`js { browser() }`), and shipped as a single `bundle.js` file.
- The bundle runs inside `androidx.javascriptengine.JavaScriptSandbox` — an isolated,
  sandboxed JS engine, not the app's own process memory.
- The bundle describes its UI as data (a small `Column` / `Text` / `Button` tree); Dootah
  parses that tree and renders it as **native Jetpack Compose**, not a WebView.
- A bundle can carry its own state and business logic (pricing, discount rules, event
  handling) and call back into the app only through an explicit, allowlisted
  `NativeBridge`.
- **Dootah never downloads or executes native binaries** — no `.dex`, `.jar`, `.apk`, or
  `.so`. The only thing fetched over the network is a JSON manifest and a JS text bundle.

This lets you change UI copy, layout, prices, and business rules in an already-installed,
Play-distributed app, then verify and roll it out without a store release.

## 2. Repository structure

```
Dootah/
├── settings.gradle.kts        # root build — the ONLY authoritative Gradle build
├── build.gradle.kts           # root — defines the buildBundle task
├── manifest.json              # example/local bundle manifest
├── dootah-android/            # the reusable OTA library — depend on this from any app
│   └── src/main/java/com/dootah/
│       ├── Dootah.kt          # public facade (initialize/checkForUpdate/loadBundle/...)
│       ├── DootahConfig.kt, DootahStatus.kt, UpdateResult.kt, BundleLoadResult.kt
│       ├── ota/               # manifest parsing, runtime compat gate, download, storage
│       ├── runtime/           # JavaScriptRuntime (sandbox/isolate), BundleEngine
│       ├── bridge/            # NativeBridge (allowlisted native commands)
│       └── ui/                # BundleUiNode/BundleRenderer/DootahBundleHost (Compose)
├── dootah-bundle/               # the Kotlin/JS bundle source — compiles to bundle.js
│   └── src/jsMain/kotlin/
│       ├── Bundle.kt            # renderScreen()/handleAction() — edit this to publish an update
│       ├── ui/                  # Column/Text/Button DSL + JSON serialization
│       ├── bridge/Native.kt     # bundle-side native call stubs (toast/log)
│       └── json/JsonEscaping.kt
└── Android-Dootah/app/         # demo/validation app (":app") — depends on dootah-android
    └── src/main/
        ├── java/com/dootah/DootahDemoApp.kt      # Dootah.initialize() in Application.onCreate
        ├── java/com/dootah/MainActivity.kt        # hosts the one demo screen
        ├── java/com/dootah/demo/OfferDemoScreen.kt # DootahBundleHost + diagnostics + native fallback
        └── assets/bundle.js                        # bundled fallback (copied by buildBundle)
```

`bundle.js` in `Android-Dootah/app/src/main/assets/` is **generated** by `./gradlew buildBundle`
from `dootah-bundle` — don't hand-edit it.

## 3. Prerequisites

- Android Studio (recent, with support for AGP 9.4.0 and Kotlin 2.4.10)
- JDK 17+ (required by AGP 9.4.0/Gradle 9.6; the Android modules themselves target Java 11
  source/target compatibility)
- Android SDK: `compileSdk 37`, `minSdk 26`
- No separate Node.js install is required — Kotlin/JS's webpack toolchain is fetched
  automatically by Gradle (state kept in `kotlin-js-store/`)
- The Gradle wrapper (`./gradlew`) pins Gradle 9.6.0 — do not need a separately installed Gradle

## 4. Run locally

```bash
git clone <this-repo>
cd Dootah
```

Open the **root** `Dootah/` directory in Android Studio (not `Android-Dootah/`) — the root
`settings.gradle.kts` is the only authoritative build and includes `:app`, `:dootah-android`,
and `:dootah-bundle`. Let Gradle sync.

Build the bundle and the demo app:

```bash
./gradlew buildBundle            # Kotlin -> Kotlin/JS webpack -> assets/bundle.js
./gradlew :app:assembleDebug     # builds Android-Dootah/app
```

Run the `:app` configuration on a device/emulator (API 26+, needs Play services for the
JavaScript sandbox to be available — see limitations below if it isn't).

**Expected result:** the app opens on one screen ("Dootah OTA Validation") showing a
diagnostics card (app version, bundle version, runtime version, bundle source, kill switch
state) followed by the offer content rendered from the **asset** `bundle.js`
(`Bundle source: ASSET`, "Weekend Offer", prices from `Bundle.kt`).

Tap **Check Dootah Update** — with the repo's default `manifestUrl`
(`DootahDemoApp.kt`), this hits a live hosted manifest. If that manifest hasn't been
republished in the current schema (see §8), the check will fail with `invalid manifest`
and the asset bundle keeps rendering — this is correct fail-closed behavior, not a bug.

## 5. Test a local OTA update

1. Edit `dootah-bundle/src/jsMain/kotlin/Bundle.kt` — e.g. change the heading, a price, or
   the discount rule in `totalPrice()`/`renderScreen()`.
2. Rebuild the bundle:
   ```bash
   ./gradlew buildBundle
   ```
3. Compute its digest:
   ```bash
   shasum -a 256 Android-Dootah/app/src/main/assets/bundle.js
   ```
4. Write a manifest with `bundleVersion` incremented and the new digest (see §8 for the
   exact schema), and publish both `bundle.js` and `manifest.json` to your configured HTTPS
   host (the one named by `manifestUrl` in `DootahConfig`/`DootahDemoApp.kt`).
5. **Do not reinstall the app.** In the running app, tap **Check Dootah Update**.
6. Verify: the diagnostics card shows the new `bundleVersion` and `Bundle source: REMOTE`,
   and the screen reflects your UI/business-logic change (heading, prices, discount rule).
   Tap the buy button and confirm the native bridge still fires (`Native.toast`/`Native.log`
   → `adb logcat -s Dootah`).
7. Force-stop and reopen the app. Confirm the remote bundle persists (`Bundle source: REMOTE`,
   same `bundleVersion`) — it survives process death because it's read from disk, not memory.

## 6. Test failure cases

Each of these must fall back safely — no crash, no blank screen — because
`DootahBundleHost`'s `fallback` parameter is required and every failure path in `Dootah.kt`
is caught and converted to `BundleLoadResult.Unavailable` / `UpdateResult.Failed`.

| Case | How to trigger | Expected result |
|---|---|---|
| Offline | Airplane mode, tap check | `UpdateFailure.NETWORK`; installed bundle keeps rendering |
| Wrong SHA | Change one hex char in `sha256`, re-publish | `FAILED_VERIFICATION`; bundle **not** replaced |
| Wrong `runtimeVersion` | Publish a `runtimeVersion` other than `"2"` with a higher `bundleVersion` | Rejected as incompatible; installed bundle keeps running |
| `enabled: false` | Publish with `"enabled": false` | `UpdateResult.Disabled`; native fallback shown, persists even offline (kill switch is persisted to disk) |
| Malformed bundle | Upload truncated/garbage `bundle.js` with a matching SHA | Digest still matches → stored, but evaluation fails → `EXECUTION_FAILED`, falls back to native |
| Timeout | Publish a bundle whose JS never returns (e.g. infinite loop) | Isolate is killed after `executionTimeoutMillis` (default 5000ms); falls back to native |
| Legacy/malformed manifest | Publish old `{version, url, sha256}` shape, or drop a required field | `INVALID_MANIFEST`; installed bundle untouched |

In every case, confirm the app never crashes and the native/asset fallback screen
remains usable.

## 7. Integrate Dootah into another Android app

**Step 1 — include the module.** Copy `dootah-android/` into the target project and add
to its `settings.gradle.kts`:

```kotlin
include(":dootah-android")
```

The module needs these version catalog entries (see `Android-Dootah/gradle/libs.versions.toml`
for exact coordinates): `android-library`, `kotlin-compose` plugins; `androidx-javascriptengine`
(1.1.0), `kotlinx-coroutines-guava` (1.10.2), `kotlinx-serialization-json` (1.9.0), plus the
Compose BOM/material3/ui/core-ktx.

**Step 2 — depend on it:**

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":dootah-android"))
}
```

`INTERNET` permission arrives automatically via manifest merging.

**Step 3 — ship an asset fallback bundle.** Copy a built `bundle.js` into the target app's
`src/main/assets/`. This is what renders before any download ever happens, and what the app
falls back to if the network is unavailable and no bundle has been downloaded yet. Without
it, Dootah reports `NO_BUNDLE_AVAILABLE`.

**Step 4 — initialize once**, in `Application.onCreate`:

```kotlin
class YourApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Dootah.initialize(
            context = this,
            config = DootahConfig(
                manifestUrl = "https://your-host/manifest.json",
                // optional, shown with their defaults:
                // assetBundleName = "bundle.js",
                // connectTimeoutMillis = 10_000,
                // readTimeoutMillis = 15_000,
                // executionTimeoutMillis = 5_000,
                // maxManifestSizeBytes = 64 * 1024,
                // maxBundleSizeBytes = 8 * 1024 * 1024,
            ),
        )
    }
}
```

**Step 5 — host one screen.** `fallback` is a required parameter — a screen backed by
remote content must always have somewhere native to fall back to:

```kotlin
@Composable
fun YourScreen() {
    val dootah = rememberDootahHostState()

    Column {
        Button(onClick = dootah::checkForUpdate) { Text("Check Dootah Update") }

        DootahBundleHost(
            state = dootah,
            loading = { CircularProgressIndicator() },
            fallback = { reason, message -> YourNativeFallbackContent() },
        )
    }
}
```

`rememberDootahHostState()` triggers an initial `load()` automatically. Calling
`dootah.checkForUpdate()` runs a manual `Dootah.checkForUpdate()` and — only if it resulted
in `UpdateResult.Updated` — reloads the bundle. You can also call `Dootah.checkForUpdate()`
directly (e.g. from a background trigger) if you don't need the Compose state holder.

That's the whole integration surface. The host app never touches `JavaScriptSandbox`,
bundle file paths, manifest parsing, hashing, or the bundle's JS module name.

## 8. Host bundles

Current model is deliberately simple: two static files behind HTTPS. Any static host works
— GitHub Pages, S3, Cloudflare Pages, or similar — as long as it serves HTTPS.

- `manifest.json` — the pointer document, fetched on every `checkForUpdate()`
- `bundle.js` — the compiled bundle, fetched only when the manifest points to a version
  newer than what's installed

Exact schema (`schemaVersion: 1`, enforced by `BundleManifestParser` — every field required,
unknown or missing fields are rejected, not defaulted):

```json
{
  "schemaVersion": 1,
  "bundleVersion": 4,
  "runtimeVersion": "2",
  "enabled": true,
  "url": "https://your-host/bundle.js",
  "sha256": "<lowercase hex SHA-256 of bundle.js, computed over raw bytes>"
}
```

- `schemaVersion` — must equal `1` today; any other value (including a newer one) is
  rejected, not guessed at.
- `bundleVersion` — positive integer; a manifest offering a version `<=` the installed one
  is treated as no update (no downgrade, no replay).
- `runtimeVersion` — exact-match compatibility token against `DOOTAH_RUNTIME_VERSION` (`"2"`
  in this codebase, `dootah-android/.../ota/RuntimeCompatibility.kt`); mismatched in either
  direction is rejected.
- `enabled` — the remote kill switch. Required, not defaulted — a manifest that omits it is
  rejected rather than silently treated as enabled.
- `url` — must be `https://`.
- `sha256` — 64 lowercase hex chars; the parser lowercases what you give it but the digest
  itself must be over the exact bytes served at `url`.
- `signature` — **optional field, reserved but not currently enforced.** Publisher (Ed25519)
  signing is not implemented yet; see §11.

## 9. Publish to Google Play

```bash
./gradlew buildBundle
```

Build a signed release AAB from `:app` as usual (Android Studio → Generate Signed Bundle,
or your existing release pipeline) with the asset `bundle.js` from the step above already in
`assets/`.

- Integrate Dootah into **one isolated, non-critical screen** to start (see §7).
- Upload the AAB to Play Console, **Internal testing** (or Closed testing) track first —
  not Production.
- Wait for Play processing, then **install the app from the Play Store**, not via `adb
  install`, so you're testing the real distribution path.
- Confirm the asset Dootah screen renders correctly and the diagnostics panel reads
  `Bundle source: ASSET`.

**Local APK/emulator testing does not establish Play Store acceptance of this mechanism.**
The steps in §10 exist specifically to validate against a Play-installed build.

## 10. Test OTA from a Play-installed build

1. Install the app from Play (§9) — once.
2. **Do not upload another AAB** for the rest of this test.
3. Edit `dootah-bundle/src/jsMain/kotlin/Bundle.kt`.
4. `./gradlew buildBundle`
5. `shasum -a 256 Android-Dootah/app/src/main/assets/bundle.js`, upload the new `bundle.js`
   to your host.
6. Publish an updated `manifest.json` with `bundleVersion` incremented and the new digest.
7. In the Play-installed app, tap **Check Dootah Update**.
8. Verify the UI and business logic changed — with **no new AAB/APK release**.
9. Force-stop the app, reopen it — confirm the bundle persists (`Bundle source: REMOTE`,
   same `bundleVersion`).
10. Test the kill switch: publish with `"enabled": false`, check, confirm native fallback
    shows; force-stop and reopen **offline** to confirm the switch survives without network
    (it's persisted on-device); republish `"enabled": true` to restore.
11. Run through the failure cases in §6 against this same Play-installed build; confirm no
    crash appears in Play Console's **Crashes and ANRs** afterward.

## 11. Current limitations

- **No publisher signatures.** SHA-256 proves the payload matches the manifest, not who
  wrote the manifest — anyone who can serve the manifest can serve code. TLS and control
  of the hosting origin are the only defenses today. Treat the hosting origin as
  production-critical, and keep bundled surfaces non-critical until this lands.
- **No automatic rollback / candidate staging.** A bundle is verified and written to disk
  *before* it is ever executed. If it stores but then fails to evaluate, the screen falls
  back to native — but the bad bundle stays on disk and will fail the same way on next
  launch. The kill switch (works offline) is the only escape hatch right now.
- **No `@Bundlable` compiler flow.** Bundles are hand-written Kotlin/JS against a fixed
  `renderScreen()`/`handleAction()` contract in `Bundle.kt` — there's no compiler plugin or
  annotation-driven authoring yet.
- **`Bundle.kt` as a single file is temporary.** It's the whole current bundle surface;
  there's no multi-screen or multi-module bundle structure yet.
- **Only the current UI primitives render OTA:** `Column`, `Text`, `Button`
  (`dootah-bundle/src/jsMain/kotlin/ui/BundleNode.kt` / `dootah-android/.../ui/BundleUiNode.kt`).
  Anything else in a bundle's UI tree will fail to parse and trigger the native fallback.
- **`ASSET_BUNDLE_VERSION` is assumed to be `1`.** The bundled asset carries no embedded
  version metadata, so if you refresh the asset `bundle.js` from a later bundle, the
  diagnostics will still report version 1 until a remote bundle is downloaded.
- Use only non-critical, reversible screens for OTA content until signing and rollback
  staging exist.

## 12. Useful commands

```bash
./gradlew buildBundle                # compile dootah-bundle -> Android-Dootah/app/src/main/assets/bundle.js
./gradlew :app:assembleDebug         # build the demo app (debug)
./gradlew :dootah-android:testDebugUnitTest   # unit tests: manifest parsing, runtime compat, update decision
./gradlew test                       # run all unit tests across modules
shasum -a 256 <file>                 # compute the sha256 a manifest must declare
./gradlew clean                      # clean all module build outputs
```
