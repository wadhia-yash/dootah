# Pravah Real-App Validation

A fail-fast experiment answering one question:

> Can a real Play-distributed Android app safely receive a Pravah OTA patch that
> changes UI and business logic without another APK/AAB release?

Everything here is disposable except the `:pravah-android` module.

---

## 1. What the library gives a host app

Public API surface, in full:

| Type | Purpose |
|---|---|
| `PravahConfig` | Manifest URL, bundled asset name, timeouts, size caps |
| `Pravah.initialize(context, config)` | Once per process |
| `Pravah.checkForUpdate(): UpdateResult` | Manual update check; never throws |
| `Pravah.loadPatch(): PatchLoadResult` | Load + render active patch; never throws |
| `Pravah.dispatchAction(action)` | Forward a UI action to the patch |
| `Pravah.status(): PravahStatus` | patchVersion, runtimeVersion, source, kill switch |
| `Pravah.shutdown()` | Release the sandbox |
| `rememberPravahHostState()` | Compose state holder |
| `PravahPatchHost(state, loading, fallback)` | Renders patch UI, or your fallback |
| `UpdateResult` / `PatchLoadResult` | Closed result sets, no string parsing |

The host app never sees `JavaScriptSandbox`, patch file paths, manifest parsing,
hashing, or the JS module name.

## 2. Integrating into an existing app

**Step 1 — copy the module.** Copy `pravah-android/` into the target project and add
to its `settings.gradle.kts`:

```kotlin
include(":pravah-android")
```

The module needs these version catalog entries (or inline the coordinates):
`android-library`, `kotlin-compose`, `androidx-javascriptengine`,
`kotlinx-coroutines-guava`, `kotlinx-serialization-json`, plus the Compose BOM.

**Step 2 — depend on it** from the app module:

```kotlin
implementation(project(":pravah-android"))
```

`INTERNET` permission arrives via manifest merging; you do not need to add it.

**Step 3 — ship a bundled patch.** Copy a built `patch.js` into the target app's
`src/main/assets/`. This is the patch that runs before any download and is the
first line of fallback. Without it, Pravah reports `NO_PATCH_AVAILABLE` and your
native fallback shows.

**Step 4 — initialize once** in `Application.onCreate`:

```kotlin
Pravah.initialize(
    context = this,
    config = PravahConfig(manifestUrl = "https://your-host/manifest.json"),
)
```

**Step 5 — host one screen.** The fallback slot is required, so a blank screen is
not expressible:

```kotlin
val pravah = rememberPravahHostState()

PravahPatchHost(
    state = pravah,
    loading = { CircularProgressIndicator() },
    fallback = { reason, message -> YourNativeScreen() },
)

Button(onClick = pravah::checkForUpdate) { Text("Check Pravah Update") }
```

That is the whole integration. Nothing else in the app changes.

## 3. Publishing a patch

```bash
./gradlew buildPatch      # Kotlin -> Kotlin/JS -> Android-Pravah/app/src/main/assets/patch.js
shasum -a 256 Android-Pravah/app/src/main/assets/patch.js
```

Upload that `patch.js` to static hosting, then publish a manifest beside it:

```json
{
  "schemaVersion": 1,
  "patchVersion": 4,
  "runtimeVersion": "1",
  "enabled": true,
  "url": "https://your-host/patch.js",
  "sha256": "<digest from shasum>"
}
```

Every field is required. A manifest missing `enabled`, `runtimeVersion` or
`schemaVersion` is rejected and the installed patch keeps running.

> **Do this before testing:** the manifest currently hosted at
> `wadhia-yash.github.io/dootah-manifest/` must be replaced with the schema
> above. The old `{version, url, sha256}` shape is rejected by design.

## 4. Safety properties in force

| Safeguard | Where |
|---|---|
| Bundled patch fallback | `PatchStore.readBundledPatch()` |
| Typed, fail-closed manifest | `PatchManifestParser` |
| `patchVersion` monotonic, no downgrade | `decideUpdate()` |
| `runtimeVersion` exact-match gate | `isRuntimeCompatible()` |
| SHA-256 over raw bytes | `PatchUpdater.install()` |
| HTTPS only | `PatchDownloader` |
| Connect/read timeouts | `PravahConfig` |
| Max patch size, enforced during read | `PatchDownloader.readAtMost()` |
| Sandbox process isolation | `JavaScriptRuntime` |
| Isolate heap cap (64 MB) | `JavaScriptRuntime.createIsolate()` |
| Execution timeout (5 s), isolate killed | `JavaScriptRuntime.evaluate()` |
| Allowlisted native bridge | `NativeBridge` |
| Atomic patch replace (temp + rename) | `PatchStore.saveDownloadedPatch()` |
| Failed update never deletes working patch | `PatchUpdater` |
| Kill switch persisted across launches | `PatchStore.isRemotelyDisabled` |
| Every failure renders native fallback | `PravahClient.renderInto()` |

## 5. Logs

Filter with `adb logcat -s Pravah`:

```
initialized: runtime version 1, manifest https://...
current patch = 3, remote patch = 4, runtime version = 1, enabled = true
downloading patch 4
hash verified for patch 4
stored patch 4
patch loaded from REMOTE (version 4)
```

Failure lines: `Pravah disabled by manifest, using bundled fallback`,
`rejecting patch N: targets runtime '2'...`, `update check failed (NETWORK)`,
`patch exceeded 5000ms, terminating isolate`, `patch execution failed, using fallback`.

---

## 6. Play Store validation checklist

Local APK testing does **not** establish Play Store acceptance. Steps 2-4 exist
specifically to test the real distribution path.

### Setup

- [ ] 1. Integrate Pravah into **one isolated, non-critical screen** of the target app (section 2)
- [ ] 2. Bump `versionCode`/`versionName`, build a release AAB, upload to **Internal testing**
- [ ] 3. Wait for Play processing, then **install from the Play Store**, not via adb
- [ ] 4. Confirm the bundled Pravah screen renders and the diagnostics show `Patch source: BUNDLED`

### Happy path

- [ ] 5. Edit `Patch.kt` — change the price to `799`, the heading to `Pravah OTA Live`, a button label, and the discount rule
- [ ] 6. `./gradlew buildPatch`, take the SHA-256, upload `patch.js`, publish a manifest with `patchVersion` incremented
- [ ] 7. Tap **Check Pravah Update** in the Play-installed app
- [ ] 8. Verify **UI changed** (heading, price, button label) and **business logic changed** (discount rule) — with **no APK/AAB release**
- [ ] 9. Tap the buy button, confirm the native toast fires (bridge still works)
- [ ] 10. Force-stop the app, reopen it
- [ ] 11. Confirm the remote patch persists and `Patch source: REMOTE`

### Kill switch

- [ ] 12. Publish the manifest with `"enabled": false`
- [ ] 13. Tap **Check Pravah Update**; expect `disabled by manifest` and the **native fallback** screen
- [ ] 14. Force-stop and reopen **with the device offline** — the fallback must persist, proving the switch survives without a network
- [ ] 15. Republish with `"enabled": true`, check again, confirm the patch returns

### Failure modes — each must fall back, never crash

- [ ] 16. **Airplane mode**, tap check → `network failure`, last good patch keeps rendering
- [ ] 17. **Invalid SHA** — publish a manifest with one hex character changed → `hash verification failed`, patch **not** replaced
- [ ] 18. **Incompatible runtime** — publish `"runtimeVersion": "2"` with a higher `patchVersion` → rejected, current patch keeps running
- [ ] 19. **Malformed patch** — upload a truncated/garbage `patch.js` with a matching SHA → native fallback, no crash
- [ ] 20. **Legacy manifest** — publish `{version, url, sha256}` → `invalid manifest`, current patch keeps running
- [ ] 21. **Oversized patch** — publish something over 8 MB → rejected during download
- [ ] 22. Clear app data, go offline, reopen → bundled patch renders

### Verdict

- [ ] 23. The app never crashed and never showed a blank screen in any of steps 16-22
- [ ] 24. The **same Play-installed build** received and ran at least two different patches
- [ ] 25. Check Play Console **Crashes and ANRs** after the run for anything Pravah-related

## 7. Known risks intentionally deferred

- **No publisher signatures.** SHA-256 proves the payload matches the manifest, not who
  wrote the manifest. Anyone who can serve the manifest can serve code. TLS and control
  of the hosting origin are the only defences right now, so **treat the hosting bucket as
  production-critical**. This is the single biggest gap and is the reason to keep this
  experiment on a non-critical screen.
- **No candidate / last-known-good staging.** A patch is verified and stored before it is
  ever executed. If it stores successfully but fails to run, the screen falls back to
  native, but the bad patch stays on disk and will fail again on next launch. The escape
  hatch is the kill switch, which does work offline.
- **Kill switch needs one successful fetch.** A device that never reaches the manifest
  cannot learn it has been switched off.
- **`BUNDLED_PATCH_VERSION` is assumed to be 1.** The bundled asset carries no metadata.
- **Google Play policy.** Pravah ships no `.dex`, `.jar`, `.apk` or `.so`, and executes only
  JavaScript inside `androidx.javascriptengine`. That is the same mechanism React Native and
  Expo OTA use, but interpretation of the policy is not something this checklist can settle.
  Keep the patched surface non-critical and reversible.
- Also deferred, per scope: staged rollout, channels, analytics, SaaS backend, compiler
  plugin / `@Patchable`, wider Compose node coverage.
