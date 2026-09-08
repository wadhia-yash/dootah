# Dootah Real-App Validation

A fail-fast experiment answering one question:

> Can a real Play-distributed Android app safely receive a Dootah OTA bundle that
> changes UI and business logic without another APK/AAB release?

Everything here is disposable except the `:dootah-android` module.

---

## 1. What the library gives a host app

Public API surface, in full:

| Type | Purpose |
|---|---|
| `DootahConfig` | Manifest URL, asset bundle name, timeouts, size caps |
| `Dootah.initialize(context, config)` | Once per process |
| `Dootah.checkForUpdate(): UpdateResult` | Manual update check; never throws |
| `Dootah.loadBundle(): BundleLoadResult` | Load + render active bundle; never throws |
| `Dootah.dispatchAction(action)` | Forward a UI action to the bundle |
| `Dootah.status(): DootahStatus` | bundleVersion, runtimeVersion, source, kill switch |
| `Dootah.shutdown()` | Release the sandbox |
| `rememberDootahHostState()` | Compose state holder |
| `DootahBundleHost(state, loading, fallback)` | Renders bundle UI, or your fallback |
| `UpdateResult` / `BundleLoadResult` | Closed result sets, no string parsing |

The host app never sees `JavaScriptSandbox`, bundle file paths, manifest parsing,
hashing, or the JS module name.

## 2. Integrating into an existing app

**Step 1 — copy the module.** Copy `dootah-android/` into the target project and add
to its `settings.gradle.kts`:

```kotlin
include(":dootah-android")
```

The module needs these version catalog entries (or inline the coordinates):
`android-library`, `kotlin-compose`, `androidx-javascriptengine`,
`kotlinx-coroutines-guava`, `kotlinx-serialization-json`, plus the Compose BOM.

**Step 2 — depend on it** from the app module:

```kotlin
implementation(project(":dootah-android"))
```

`INTERNET` permission arrives via manifest merging; you do not need to add it.

**Step 3 — ship an asset bundle.** Copy a built `bundle.js` into the target app's
`src/main/assets/`. This is the bundle that runs before any download and is the
first line of fallback. Without it, Dootah reports `NO_BUNDLE_AVAILABLE` and your
native fallback shows.

**Step 4 — initialize once** in `Application.onCreate`:

```kotlin
Dootah.initialize(
    context = this,
    config = DootahConfig(manifestUrl = "https://your-host/manifest.json"),
)
```

**Step 5 — host one screen.** The fallback slot is required, so a blank screen is
not expressible:

```kotlin
val dootah = rememberDootahHostState()

DootahBundleHost(
    state = dootah,
    loading = { CircularProgressIndicator() },
    fallback = { reason, message -> YourNativeScreen() },
)

Button(onClick = dootah::checkForUpdate) { Text("Check Dootah Update") }
```

That is the whole integration. Nothing else in the app changes.

## 3. Publishing a bundle

```bash
./gradlew buildBundle      # Kotlin -> Kotlin/JS -> Android-Dootah/app/src/main/assets/bundle.js
shasum -a 256 Android-Dootah/app/src/main/assets/bundle.js
```

Upload that `bundle.js` to static hosting, then publish a manifest beside it:

```json
{
  "schemaVersion": 1,
  "bundleVersion": 4,
  "runtimeVersion": "1",
  "enabled": true,
  "url": "https://your-host/bundle.js",
  "sha256": "<digest from shasum>"
}
```

Every field is required. A manifest missing `enabled`, `runtimeVersion` or
`schemaVersion` is rejected and the installed bundle keeps running.

> **Do this before testing:** the manifest currently hosted at
> `wadhia-yash.github.io/dootah-manifest/` must be replaced with the schema
> above, and the file it points to renamed from `patch.js` to `bundle.js`. The
> old `{version, url, sha256}` shape is rejected by design.

## 4. Safety properties in force

| Safeguard | Where |
|---|---|
| Asset bundle fallback | `BundleStore.readAssetBundle()` |
| Typed, fail-closed manifest | `BundleManifestParser` |
| `bundleVersion` monotonic, no downgrade | `decideUpdate()` |
| `runtimeVersion` exact-match gate | `isRuntimeCompatible()` |
| SHA-256 over raw bytes | `BundleUpdater.install()` |
| HTTPS only | `BundleDownloader` |
| Connect/read timeouts | `DootahConfig` |
| Max bundle size, enforced during read | `BundleDownloader.readAtMost()` |
| Sandbox process isolation | `JavaScriptRuntime` |
| Isolate heap cap (64 MB) | `JavaScriptRuntime.createIsolate()` |
| Execution timeout (5 s), isolate killed | `JavaScriptRuntime.evaluate()` |
| Allowlisted native bridge | `NativeBridge` |
| Atomic bundle replace (temp + rename) | `BundleStore.saveDownloadedBundle()` |
| Failed update never deletes working bundle | `BundleUpdater` |
| Kill switch persisted across launches | `BundleStore.isRemotelyDisabled` |
| Every failure renders native fallback | `DootahClient.renderInto()` |

## 5. Logs

Filter with `adb logcat -s Dootah`:

```
initialized: runtime version 1, manifest https://...
current bundle = 3, remote bundle = 4, runtime version = 1, enabled = true
downloading bundle 4
hash verified for bundle 4
stored bundle 4
bundle loaded from REMOTE (version 4)
```

Failure lines: `Dootah disabled by manifest, using the bundled asset`,
`rejecting bundle N: targets runtime '2'...`, `update check failed (NETWORK)`,
`bundle exceeded 5000ms, terminating isolate`, `bundle execution failed, using fallback`.

---

## 6. Play Store validation checklist

Local APK testing does **not** establish Play Store acceptance. Steps 2-4 exist
specifically to test the real distribution path.

### Setup

- [ ] 1. Integrate Dootah into **one isolated, non-critical screen** of the target app (section 2)
- [ ] 2. Bump `versionCode`/`versionName`, build a release AAB, upload to **Internal testing**
- [ ] 3. Wait for Play processing, then **install from the Play Store**, not via adb
- [ ] 4. Confirm the asset Dootah screen renders and the diagnostics show `Bundle source: ASSET`

### Happy path

- [ ] 5. Edit `Bundle.kt` — change the price to `799`, the heading to `Dootah OTA Live`, a button label, and the discount rule
- [ ] 6. `./gradlew buildBundle`, take the SHA-256, upload `bundle.js`, publish a manifest with `bundleVersion` incremented
- [ ] 7. Tap **Check Dootah Update** in the Play-installed app
- [ ] 8. Verify **UI changed** (heading, price, button label) and **business logic changed** (discount rule) — with **no APK/AAB release**
- [ ] 9. Tap the buy button, confirm the native toast fires (bridge still works)
- [ ] 10. Force-stop the app, reopen it
- [ ] 11. Confirm the remote bundle persists and `Bundle source: REMOTE`

### Kill switch

- [ ] 12. Publish the manifest with `"enabled": false`
- [ ] 13. Tap **Check Dootah Update**; expect `disabled by manifest` and the **native fallback** screen
- [ ] 14. Force-stop and reopen **with the device offline** — the fallback must persist, proving the switch survives without a network
- [ ] 15. Republish with `"enabled": true`, check again, confirm the bundle returns

### Failure modes — each must fall back, never crash

- [ ] 16. **Airplane mode**, tap check → `network failure`, last good bundle keeps rendering
- [ ] 17. **Invalid SHA** — publish a manifest with one hex character changed → `hash verification failed`, bundle **not** replaced
- [ ] 18. **Incompatible runtime** — publish `"runtimeVersion": "2"` with a higher `bundleVersion` → rejected, current bundle keeps running
- [ ] 19. **Malformed bundle** — upload a truncated/garbage `bundle.js` with a matching SHA → native fallback, no crash
- [ ] 20. **Legacy manifest** — publish `{version, url, sha256}` → `invalid manifest`, current bundle keeps running
- [ ] 21. **Oversized bundle** — publish something over 8 MB → rejected during download
- [ ] 22. Clear app data, go offline, reopen → asset bundle renders

### Verdict

- [ ] 23. The app never crashed and never showed a blank screen in any of steps 16-22
- [ ] 24. The **same Play-installed build** received and ran at least two different bundles
- [ ] 25. Check Play Console **Crashes and ANRs** after the run for anything Dootah-related

## 7. Known risks intentionally deferred

- **No publisher signatures.** SHA-256 proves the payload matches the manifest, not who
  wrote the manifest. Anyone who can serve the manifest can serve code. TLS and control
  of the hosting origin are the only defences right now, so **treat the hosting bucket as
  production-critical**. This is the single biggest gap and is the reason to keep this
  experiment on a non-critical screen.
- **No candidate / last-known-good staging.** A bundle is verified and stored before it is
  ever executed. If it stores successfully but fails to run, the screen falls back to
  native, but the bad bundle stays on disk and will fail again on next launch. The escape
  hatch is the kill switch, which does work offline.
- **Kill switch needs one successful fetch.** A device that never reaches the manifest
  cannot learn it has been switched off.
- **`ASSET_BUNDLE_VERSION` is assumed to be 1.** The bundled asset carries no metadata.
- **Google Play policy.** Dootah ships no `.dex`, `.jar`, `.apk` or `.so`, and executes only
  JavaScript inside `androidx.javascriptengine`. That is the same mechanism React Native and
  Expo OTA use, but interpretation of the policy is not something this checklist can settle.
  Keep the bundled surface non-critical and reversible.
- Also deferred, per scope: staged rollout, channels, analytics, SaaS backend, compiler
  plugin / `@Bundlable`, wider Compose node coverage.
