# Pravah

A CodePush-style over-the-air (OTA) update system for native Android/Kotlin apps.

## 1. What Pravah is

Pravah lets a native Android app fetch and run a small **Kotlin/JS patch** at runtime,
without shipping a new APK/AAB:

- The patch is written in Kotlin, compiled to JavaScript via Kotlin Multiplatform
  (`js { browser() }`), and shipped as a single `patch.js` bundle.
- The bundle runs inside `androidx.javascriptengine.JavaScriptSandbox` — an isolated,
  sandboxed JS engine, not the app's own process memory.
- The patch describes its UI as data (a small `Column` / `Text` / `Button` tree); Pravah
  parses that tree and renders it as **native Jetpack Compose**, not a WebView.
- A patch can carry its own state and business logic (pricing, discount rules, event
  handling) and call back into the app only through an explicit, allowlisted
  `NativeBridge`.
- **Pravah never downloads or executes native binaries** — no `.dex`, `.jar`, `.apk`, or
  `.so`. The only thing fetched over the network is a JSON manifest and a JS text bundle.

This lets you change UI copy, layout, prices, and business rules in an already-installed,
Play-distributed app, then verify and roll it out without a store release.

## 2. Repository structure

```
Pravah/
├── settings.gradle.kts        # root build — the ONLY authoritative Gradle build
├── build.gradle.kts           # root — defines the buildPatch task
├── manifest.json              # example/local patch manifest
├── pravah-android/            # the reusable OTA library — depend on this from any app
│   └── src/main/java/com/pravah/
│       ├── Pravah.kt          # public facade (initialize/checkForUpdate/loadPatch/...)
│       ├── PravahConfig.kt, PravahStatus.kt, UpdateResult.kt, PatchLoadResult.kt
│       ├── ota/               # manifest parsing, runtime compat gate, download, storage
│       ├── runtime/           # JavaScriptRuntime (sandbox/isolate), PatchEngine
│       ├── bridge/            # NativeBridge (allowlisted native commands)
│       └── ui/                # PatchUiNode/PatchRenderer/PravahPatchHost (Compose)
├── patch-bundle/               # the Kotlin/JS patch source — compiles to patch.js
│   └── src/jsMain/kotlin/
│       ├── Patch.kt            # renderScreen()/handleAction() — edit this to publish a patch
│       ├── ui/                 # Column/Text/Button DSL + JSON serialization
│       ├── bridge/Native.kt     # patch-side native call stubs (toast/log)
│       └── json/JsonEscaping.kt
└── Android-Pravah/app/         # demo/validation app (":app") — depends on pravah-android
    └── src/main/
        ├── java/com/pravah/PravahDemoApp.kt      # Pravah.initialize() in Application.onCreate
        ├── java/com/pravah/MainActivity.kt        # hosts the one demo screen
        ├── java/com/pravah/demo/OfferDemoScreen.kt # PravahPatchHost + diagnostics + native fallback
        └── assets/patch.js                        # bundled fallback patch (copied by buildPatch)
```

`patch.js` in `Android-Pravah/app/src/main/assets/` is **generated** by `./gradlew buildPatch`
from `patch-bundle` — don't hand-edit it.

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
cd Pravah
```

Open the **root** `Pravah/` directory in Android Studio (not `Android-Pravah/`) — the root
`settings.gradle.kts` is the only authoritative build and includes `:app`, `:pravah-android`,
and `:patch-bundle`. Let Gradle sync.

Build the patch bundle and the demo app:

```bash
./gradlew buildPatch            # Kotlin -> Kotlin/JS webpack -> assets/patch.js
./gradlew :app:assembleDebug    # builds Android-Pravah/app
```

Run the `:app` configuration on a device/emulator (API 26+, needs Play services for the
JavaScript sandbox to be available — see limitations below if it isn't).

**Expected result:** the app opens on one screen ("Pravah OTA Validation") showing a
diagnostics card (app version, patch version, runtime version, patch source, kill switch
state) followed by the offer content rendered from the **bundled** `patch.js`
(`Patch source: BUNDLED`, "Weekend Offer", prices from `Patch.kt`).

Tap **Check Pravah Update** — with the repo's default `manifestUrl`
(`PravahDemoApp.kt`), this hits a live hosted manifest. If that manifest hasn't been
republished in the current schema (see §8), the check will fail with `invalid manifest`
and the bundled patch keeps rendering — this is correct fail-closed behavior, not a bug.

## 5. Test a local OTA update

1. Edit `patch-bundle/src/jsMain/kotlin/Patch.kt` — e.g. change the heading, a price, or
   the discount rule in `totalPrice()`/`renderScreen()`.
2. Rebuild the bundle:
   ```bash
   ./gradlew buildPatch
   ```
3. Compute its digest:
   ```bash
   shasum -a 256 Android-Pravah/app/src/main/assets/patch.js
   ```
4. Write a manifest with `patchVersion` incremented and the new digest (see §8 for the
   exact schema), and publish both `patch.js` and `manifest.json` to your configured HTTPS
   host (the one named by `manifestUrl` in `PravahConfig`/`PravahDemoApp.kt`).
5. **Do not reinstall the app.** In the running app, tap **Check Pravah Update**.
6. Verify: the diagnostics card shows the new `patchVersion` and `Patch source: REMOTE`,
   and the screen reflects your UI/business-logic change (heading, prices, discount rule).
   Tap the buy button and confirm the native bridge still fires (`Native.toast`/`Native.log`
   → `adb logcat -s Pravah`).
7. Force-stop and reopen the app. Confirm the remote patch persists (`Patch source: REMOTE`,
   same `patchVersion`) — it survives process death because it's read from disk, not memory.

## 6. Test failure cases

Each of these must fall back safely — no crash, no blank screen — because
`PravahPatchHost`'s `fallback` parameter is required and every failure path in `Pravah.kt`
is caught and converted to `PatchLoadResult.Unavailable` / `UpdateResult.Failed`.

| Case | How to trigger | Expected result |
|---|---|---|
| Offline | Airplane mode, tap check | `UpdateFailure.NETWORK`; installed patch keeps rendering |
| Wrong SHA | Change one hex char in `sha256`, re-publish | `FAILED_VERIFICATION`; patch **not** replaced |
| Wrong `runtimeVersion` | Publish `"runtimeVersion": "2"` with a higher `patchVersion` | Rejected as incompatible; installed patch keeps running |
| `enabled: false` | Publish with `"enabled": false` | `UpdateResult.Disabled`; native fallback shown, persists even offline (kill switch is persisted to disk) |
| Malformed patch | Upload truncated/garbage `patch.js` with a matching SHA | Digest still matches → stored, but evaluation fails → `EXECUTION_FAILED`, falls back to native |
| Timeout | Publish a patch whose JS never returns (e.g. infinite loop) | Isolate is killed after `executionTimeoutMillis` (default 5000ms); falls back to native |
| Legacy/malformed manifest | Publish old `{version, url, sha256}` shape, or drop a required field | `INVALID_MANIFEST`; installed patch untouched |

In every case, confirm the app never crashes and the native/bundled fallback screen
remains usable.

## 7. Integrate Pravah into another Android app

**Step 1 — include the module.** Copy `pravah-android/` into the target project and add
to its `settings.gradle.kts`:

```kotlin
include(":pravah-android")
```

The module needs these version catalog entries (see `Android-Pravah/gradle/libs.versions.toml`
for exact coordinates): `android-library`, `kotlin-compose` plugins; `androidx-javascriptengine`
(1.1.0), `kotlinx-coroutines-guava` (1.10.2), `kotlinx-serialization-json` (1.9.0), plus the
Compose BOM/material3/ui/core-ktx.

**Step 2 — depend on it:**

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":pravah-android"))
}
```

`INTERNET` permission arrives automatically via manifest merging.

**Step 3 — ship a bundled fallback patch.** Copy a built `patch.js` into the target app's
`src/main/assets/`. This is what renders before any download ever happens, and what the app
falls back to if the network is unavailable and no patch has been downloaded yet. Without
it, Pravah reports `NO_PATCH_AVAILABLE`.

**Step 4 — initialize once**, in `Application.onCreate`:

```kotlin
class YourApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Pravah.initialize(
            context = this,
            config = PravahConfig(
                manifestUrl = "https://your-host/manifest.json",
                // optional, shown with their defaults:
                // bundledPatchAsset = "patch.js",
                // connectTimeoutMillis = 10_000,
                // readTimeoutMillis = 15_000,
                // executionTimeoutMillis = 5_000,
                // maxManifestSizeBytes = 64 * 1024,
                // maxPatchSizeBytes = 8 * 1024 * 1024,
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
    val pravah = rememberPravahHostState()

    Column {
        Button(onClick = pravah::checkForUpdate) { Text("Check Pravah Update") }

        PravahPatchHost(
            state = pravah,
            loading = { CircularProgressIndicator() },
            fallback = { reason, message -> YourNativeFallbackContent() },
        )
    }
}
```

`rememberPravahHostState()` triggers an initial `load()` automatically. Calling
`pravah.checkForUpdate()` runs a manual `Pravah.checkForUpdate()` and — only if it resulted
in `UpdateResult.Updated` — reloads the patch. You can also call `Pravah.checkForUpdate()`
directly (e.g. from a background trigger) if you don't need the Compose state holder.

That's the whole integration surface. The host app never touches `JavaScriptSandbox`,
patch file paths, manifest parsing, hashing, or the patch's JS module name.

## 8. Host patches

Current model is deliberately simple: two static files behind HTTPS. Any static host works
— GitHub Pages, S3, Cloudflare Pages, or similar — as long as it serves HTTPS.

- `manifest.json` — the pointer document, fetched on every `checkForUpdate()`
- `patch.js` — the compiled bundle, fetched only when the manifest points to a version
  newer than what's installed

Exact schema (`schemaVersion: 1`, enforced by `PatchManifestParser` — every field required,
unknown or missing fields are rejected, not defaulted):

```json
{
  "schemaVersion": 1,
  "patchVersion": 4,
  "runtimeVersion": "1",
  "enabled": true,
  "url": "https://your-host/patch.js",
  "sha256": "<lowercase hex SHA-256 of patch.js, computed over raw bytes>"
}
```

- `schemaVersion` — must equal `1` today; any other value (including a newer one) is
  rejected, not guessed at.
- `patchVersion` — positive integer; a manifest offering a version `<=` the installed one
  is treated as no update (no downgrade, no replay).
- `runtimeVersion` — exact-match compatibility token against `PRAVAH_RUNTIME_VERSION` (`"1"`
  in this codebase, `pravah-android/.../ota/RuntimeCompatibility.kt`); mismatched in either
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
./gradlew buildPatch
```

Bundle a signed release AAB from `:app` as usual (Android Studio → Generate Signed Bundle,
or your existing release pipeline) with the bundled `patch.js` from step above already in
`assets/`.

- Integrate Pravah into **one isolated, non-critical screen** to start (see §7).
- Upload the AAB to Play Console, **Internal testing** (or Closed testing) track first —
  not Production.
- Wait for Play processing, then **install the app from the Play Store**, not via `adb
  install`, so you're testing the real distribution path.
- Confirm the bundled Pravah screen renders correctly and the diagnostics panel reads
  `Patch source: BUNDLED`.

**Local APK/emulator testing does not establish Play Store acceptance of this mechanism.**
The steps in §10 exist specifically to validate against a Play-installed build.

## 10. Test OTA from a Play-installed build

1. Install the app from Play (§9) — once.
2. **Do not upload another AAB** for the rest of this test.
3. Edit `patch-bundle/src/jsMain/kotlin/Patch.kt`.
4. `./gradlew buildPatch`
5. `shasum -a 256 Android-Pravah/app/src/main/assets/patch.js`, upload the new `patch.js`
   to your host.
6. Publish an updated `manifest.json` with `patchVersion` incremented and the new digest.
7. In the Play-installed app, tap **Check Pravah Update**.
8. Verify the UI and business logic changed — with **no new AAB/APK release**.
9. Force-stop the app, reopen it — confirm the patch persists (`Patch source: REMOTE`,
   same `patchVersion`).
10. Test the kill switch: publish with `"enabled": false`, check, confirm native fallback
    shows; force-stop and reopen **offline** to confirm the switch survives without network
    (it's persisted on-device); republish `"enabled": true` to restore.
11. Run through the failure cases in §6 against this same Play-installed build; confirm no
    crash appears in Play Console's **Crashes and ANRs** afterward.

## 11. Current limitations

- **No publisher signatures.** SHA-256 proves the payload matches the manifest, not who
  wrote the manifest — anyone who can serve the manifest can serve code. TLS and control
  of the hosting origin are the only defenses today. Treat the hosting origin as
  production-critical, and keep patched surfaces non-critical until this lands.
- **No automatic rollback / candidate staging.** A patch is verified and written to disk
  *before* it is ever executed. If it stores but then fails to evaluate, the screen falls
  back to native — but the bad patch stays on disk and will fail the same way on next
  launch. The kill switch (works offline) is the only escape hatch right now.
- **No `@Patchable` compiler flow.** Patches are hand-written Kotlin/JS against a fixed
  `renderScreen()`/`handleAction()` contract in `Patch.kt` — there's no compiler plugin or
  annotation-driven authoring yet.
- **`Patch.kt` as a single file is temporary.** It's the whole current patch surface; there's
  no multi-screen or multi-module patch structure yet.
- **Only the current UI primitives render OTA:** `Column`, `Text`, `Button`
  (`patch-bundle/src/jsMain/kotlin/ui/PatchNode.kt` / `pravah-android/.../ui/PatchUiNode.kt`).
  Anything else in a patch's UI tree will fail to parse and trigger the native fallback.
- **`BUNDLED_PATCH_VERSION` is assumed to be `1`.** The bundled asset carries no embedded
  version metadata, so if you refresh the bundled `patch.js` from a later patch, the
  diagnostics will still report version 1 until a remote patch is downloaded.
- Use only non-critical, reversible screens for OTA content until signing and rollback
  staging exist.

## 12. Useful commands

```bash
./gradlew buildPatch                 # compile patch-bundle -> Android-Pravah/app/src/main/assets/patch.js
./gradlew :app:assembleDebug         # build the demo app (debug)
./gradlew :pravah-android:testDebugUnitTest   # unit tests: manifest parsing, runtime compat, update decision
./gradlew test                       # run all unit tests across modules
shasum -a 256 <file>                 # compute the sha256 a manifest must declare
./gradlew clean                      # clean all module build outputs
```
