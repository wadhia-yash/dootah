> Historical runtime experiment and maintainer producer workspace. Do not use this
> as current integration instructions; start with the [V2 quickstart](../../docs/getting-started/quickstart.md).

# Compiler-free V2 runtime spike

This directory is Dootah-owned implementation infrastructure. It is not the Phase 3
consumer SDK or an integration guide for customer applications. Do not run V1's
Gradle build or compiler plugin for this spike.

`android/app/src/main/java/dev/dootah/v2/spike/MainActivity.kt` renders a real Compose
`ExampleScreen(title)`. MainApplication starts an Expo ReactHost without creating an
RN surface and forwards a validated brownfield message to a StateFlow. `index.js`
sends a title; it registers no React component and contains no JSX.

## Build

Use JDK 21 and an installed Android SDK. From this directory:

```sh
npm ci
cd android
```

The release variant enables the actual update runtime and embeds Hermes bytecode;
Metro is unnecessary at runtime. The generated native scaffold has been modified
for Compose: do not rerun `expo prebuild --clean` over it. The local debug signing
key is not a distributable production key. If absent, generate a local test key:

```sh
keytool -genkeypair -keystore app/debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' \
  -keyalg RSA -keysize 2048 -validity 10000
```

After the test key exists, build from `android`:

```sh
NODE_ENV=production ./gradlew :app:assembleRelease -PreactNativeArchitectures=arm64-v8a --max-workers=2
```

The existing arm64 release APK has passed assembly and signature verification;
see `../../docs/v2/PHASE2C_DEVICE_PROOF.md` for the signed baseline and device results.

## Signed xprem proof (Phase 2C)

The current sample configuration pins the existing proof app's **public** certificate
and uses `http://127.0.0.1:3100/manifest`, app header
`34e81d97-661f-4f3b-ad2c-8d4e7a6546a6`, and channel `development`.
Start the preserved backend using [SELF_HOSTED_SERVER.md](../../docs/v2/SELF_HOSTED_SERVER.md).
Use `adb reverse tcp:3100 tcp:3100`. No publisher credential belongs in the app.
The checked-in certificate is a local proof trust anchor, not a production key policy.

Before establishing an install-once baseline, verify that the APK's
`assets/app.manifest` has a fresh identity when changing native update configuration.
The added Gradle file inputs prevent the pinned Expo resource task from silently
reusing the old embedded identity for changed URL/headers/certificate configuration.
Keep JDK 21 and `ANDROID_HOME` set as described above.

To prepare a remote title without rebuilding the APK, from this directory:

```sh
EXPO_PUBLIC_DOOTAH_TITLE='Dootah xprem OTA 1' EXPO_NO_TELEMETRY=1 CI=1 \
  node node_modules/expo/bin/cli export --platform android --clear --output-dir /absolute/export-directory
node -e 'const fs = require("fs"); fs.writeFileSync(process.argv[1] + "/expoConfig.json", JSON.stringify(require("./app.json").expo))' /absolute/export-directory
node ../server/publish.mjs http://127.0.0.1:3100 \
  /path/to/private/server-state/credentials.json /absolute/export-directory
```

`--clear` is necessary for this environment-variable title probe: a cached Metro
transform retained the earlier title without it. This clears **host Metro cache**,
not app data. Inspect the exported bundle before publishing. Repeat with a new
export directory and OTA 2 title. Publishing uses xprem's existing upload/finalize
API, with signing performed only by the server. Full results and failure-injection
method are in [PHASE2C_DEVICE_PROOF.md](../../docs/v2/PHASE2C_DEVICE_PROOF.md).

## Historical Phase 1 fixture

Use a separate checkout of `expo/custom-expo-updates-server` at
`feb29fc4ac1f5eb011bdfe95391190a91da4bb31`. In its `expo-updates-server` directory:

```sh
npm install --no-audit --no-fund
HOSTNAME=http://127.0.0.1:3000 NEXT_TELEMETRY_DISABLED=1 \
  npx next dev --hostname 127.0.0.1 --port 3000
```

This historical upstream fixture is local-only, has outdated dependencies, and is
not a production server. Phase 1 used unsigned loopback HTTP. It did not disable an installed signing
policy or prove production signing. The current sample uses the signed xprem
configuration above.

The Phase 1 checkout is `~/.dootah-v2/expo-custom-server`. Its manifest URL was
loopback; the historical ADB forwarding was:

```sh
adb reverse tcp:3000 tcp:3000
```

## Physical-device sequence

Passed on Samsung SM-E426B / Android 13; see the evidence document above. Use an
Android 10+ device for this frame-commit health adapter. The Activity must remain
AppCompatActivity because Expo Modules checks its type on resume.

1. With the fixture stopped or a rollback directive latest, install the baseline APK
   **once** using `adb install android/app/build/outputs/apk/release/app-release.apk`.
   Launch `dev.dootah.v2.spike/.MainActivity`; record original text, package update
   time, APK SHA-256 and device identity. Confirm the baseline JS message in logcat.
2. Start the fixture. From this directory publish the changed bundle:

   ```sh
   node publish.mjs /absolute/path/to/expo-updates-server 'Dootah OTA V2'
   ```

3. Restart the existing app to trigger its background update check, wait for download,
   then restart to activate the cached update. Capture native Compose text and update
   identity. Never reinstall or clear app data during the sequence.
4. Restart online again; verify the same OTA. Stop the local fixture and restart;
   verify cached OTA with failed network access. Preserve logs and screenshots.
5. Restart the fixture and publish `--rollback` instead of a title. This uses the
   upstream `rollBackToEmbedded` marker, not a custom rollback implementation.
   Check/download, restart and verify baseline text; recheck install identity.

This initial launch policy activates downloaded changes on a subsequent process
start. Live in-process reload is not claimed. Check headless Expo recovery/health
through the pinned native-renderer patch described in `patches/README.md`.
It acknowledges an Android-committed Compose frame through Expo's existing recovery
transition, without synthesizing ReactMarker events. This is a Dootah-maintained
patch, not an upstream public API.

Measure APK bytes against a matching Compose-only control, median `am start -W`
cold-start timing over several force-stops, and steady `dumpsys meminfo` PSS on the
same physical device. Keep control and spike build settings/ABI consistent. Do not
infer overhead from JS bundle size.

The accepted sequence includes all these physical steps. A new environment must
reproduce them; assembly alone does not prove OTA.

## Compose-only control

Build from this directory using the same JDK/SDK and existing Gradle wrapper:

```sh
./android/gradlew -p control assembleRelease --max-workers=2
adb install 'control/build/outputs/apk/release/Dootah Compose Control-release.apk'
```

Both apps use the same Compose/Activity/AppCompat dependencies and unminified
release settings. Force-stop both apps before each alternating measurement;
`am start -W -n <package>/.MainActivity` supplies initial Activity TotalTime,
then `dumpsys meminfo <package>` after 12 seconds supplies TOTAL PSS. Package names
are `dev.dootah.v2.control` and `dev.dootah.v2.spike`. Initial Activity timing is
not full JS readiness. Repeat three times each and compare medians.

For offline acceptance while preserving wireless ADB:

```sh
adb shell cmd connectivity set-package-networking-enabled false dev.dootah.v2.spike
adb reverse --remove tcp:3000
# Force-stop/relaunch and capture cached title and failed update-check logs.
adb shell cmd connectivity set-package-networking-enabled true dev.dootah.v2.spike
adb reverse tcp:3000 tcp:3000
```

Restore networking before rollback. Never clear data or reinstall between baseline
and OTA/rollback. Capture `dumpsys package` lastUpdateTime and installed base.apk
SHA-256 before/after to establish installation identity.
