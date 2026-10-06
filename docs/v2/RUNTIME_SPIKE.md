> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# V2 runtime spike evidence

2026-09-26. **Phase 1: PASSED on a physical device.** Phase 0 is `c70911a`.
Phase 2C signed xprem evidence is recorded in [PHASE2C_DEVICE_PROOF.md](PHASE2C_DEVICE_PROOF.md).
Phase 3 has not started. This is an architectural spike, not a consumer SDK
or production release. No V1 compiler or IR manipulation is involved.

## Device and fixed installation

Samsung SM-E426B, Android 13 / API 33, arm64-v8a, wireless ADB.
The originally built APK was installed first and failed: Expo Modules requires an
AppCompatActivity on host resume. It executed the embedded JS but crashed through
Expo's error recovery. The user explicitly authorized a corrected baseline
replacement. MainActivity now extends AppCompatActivity and still uses actual
Compose `setContent` / `ExampleScreen` / `Text`, with no React surface or JSX.

The corrected APK was installed at **20:02:09 IST**. Before starting the successful
sequence, the sample's data was cleared once because the original crash had left
its embedded update marked failed. **No APK reinstall, replacement, or data clear
occurred from the clean baseline through OTA, online/offline restarts, and rollback.**
Android `lastUpdateTime` stayed 20:02:09 across every recorded stage. The installed
base.apk SHA-256 equals the local artifact hash:
`30b43e3974a59774542af8ca4017d34b1dcb4813cc6b11268872ac31e4605e32`.
The earlier failed APK hash was
`e2d2c1d399bf296068a77857e9d547fc9f33dca9de951432da89ccb41110318a`.

## Physical acceptance

| Step | Result |
| --- | --- |
| Embedded baseline | PASS: Compose shows `Dootah Native Baseline`; JS bridge and native committed-frame acknowledgement recorded |
| Self-hosted publish | PASS: pinned upstream Expo fixture on localhost, reached through ADB reverse; no EAS service/account |
| Discover/download | PASS: CheckCompleteAvailable, successful bundle asset, DownloadComplete; baseline stays visible until next process launch |
| Execute/activate | PASS: Hermes bridge receives `Dootah OTA V2` from downloaded update; native Compose displays it |
| Online force-stop/restart | PASS: same OTA value and update ID |
| Offline force-stop/restart | PASS: disabled networking for only the spike UID and removed ADB reverse; Expo logs failed network check, cached OTA still renders and acknowledges health |
| Remote rollback | PASS: upstream `rollBackToEmbedded` directive, then process restart restores embedded title and update ID |
| Native health | PASS: committed Compose frame followed by Expo's own successful-launch record on every accepted stage |
| Fixed APK identity | PASS: unchanged package update time and installed APK hash |

Embedded update: `abbbd47f-a56c-46e8-a121-f3f1d4b4ca3a`.
Remote update: `8ab574db-4c1a-d433-4bbc-5a52b09d9e0c`.
Fixture release: `1790433181487`; rollback: `1790433287563`.
Remote Hermes bundle: 1,433,590 bytes; SHA-256 base64url
`XFvqGrkn6WAwNw8VBH9NTTnIYeBhia_a3Brhz9l2FtI` matches the advertised asset hash.
The fixture derives update identity from export metadata: it is a local demo,
not evidence of production release identity or storage guarantees.

Durable evidence: [receipt and measurements](evidence/phase1-20260926/receipt.json),
[targeted lifecycle events](evidence/phase1-20260926/lifecycle.txt),
[baseline](evidence/phase1-20260926/baseline.png),
[OTA](evidence/phase1-20260926/ota.png),
[offline](evidence/phase1-20260926/offline.png),
[rollback](evidence/phase1-20260926/rollback.png).
Lifecycle evidence includes discovery/download, JS execution/bridge, native frame
commit, Expo acknowledgement and rollback. Raw process logs, UI hierarchies,
package dumps and build logs remain in `/tmp/dootah-v2-device-proof`.

## Native-renderer health integration

The pinned Expo SDK has no public native-renderer success API. Its documented
[recovery transition](https://docs.expo.dev/eas-update/error-recovery/) is normally
triggered by an RN content-appearance marker. A three-file, MIT-licensed,
version-pinned patch exposes `EnabledUpdatesController.onNativeContentRendered(UUID)`.
This is **Dootah-maintained, not an upstream-supported API**.

A validated JS message supplies the render value. After Android's hardware frame
commit callback for that Compose draw, Dootah checks that the value is still
current and passes the launch ID. The adapter rejects stale IDs/emergency launches
and deduplicates success. It calls Expo's existing recovery transition, which
records success through its own delegate and retains its existing ten-second
error-monitoring window. Logs confirm that delegate completed after Compose
committed. No React UI, synthetic ReactMarker, direct database write from Dootah,
or disabled recovery is used. The placeholder title alone cannot acknowledge.

See [patch rationale and license](../../v2/runtime-spike/patches/README.md).
`patch-package` applies only the three source files and fails closed on patch drift;
application to pristine npm source was verified byte-for-byte against build inputs.
The current frame receipt requires hardware-rendered Android 10+; older/software
renderers deliberately do not acknowledge. The spike's declared minimum SDK 24
is not a claim of verified health integration on those older devices.
Automatic bad-update crash recovery was not exhaustively fault-injected in the
corrected APK; the first-render transition and remote rollback were exercised.
Production support needs an upstream API or maintained adapter with broader tests.

## Approximate overhead

`v2/runtime-spike/control` is a native Compose-only AppCompatActivity application.
Both release builds use AGP 8.12.0, Gradle 9.3.1, Kotlin/Compose compiler 2.1.20,
compile/target SDK 36, min SDK 24, activity-compose 1.10.1, AppCompat 1.7.1,
Material3 1.3.1, no shrinking/minification and local test signing. Spike includes
only arm64-v8a native libraries; control is restricted to the same ABI.

| Measurement | Compose control | Spike | Approximate increase |
| --- | ---: | ---: | ---: |
| APK / installed base.apk | 7,260,041 bytes (6.92 MiB) | 34,008,015 bytes (32.43 MiB) | 26,747,974 bytes (25.51 MiB) |
| Median cold-process Activity launch | 194 ms | 255 ms | 61 ms |
| Median process PSS, 12s after launch | 44,411 KiB (43.37 MiB) | 75,734 KiB (73.96 MiB) | 31,323 KiB (30.59 MiB) |

Three alternating runs each, other test app force-stopped. Control startup samples:
206/190/194 ms; spike: 257/255/236 ms. PSS samples and raw meminfo reports are in the
evidence directory. `am start -W` measures initial Activity display, **not complete
JS/OTA readiness**; native placeholder rendering can precede JS. Normal OS caches
were retained, with no reboot or compilation-mode normalization. These are rough
single-device observations, not benchmark claims. APK delta includes the bundled
JS and Expo/RN scaffold; it is not an optimized SDK size estimate. Base.apk bytes
exclude ART/data/cache storage.

## Reproduction and limits

Follow [runtime spike README](../../v2/runtime-spike/README.md). Corrected build:

```sh
# v2/runtime-spike, JDK 21 and Android SDK configured
NODE_ENV=production ./android/gradlew -p android :app:assembleRelease -PreactNativeArchitectures=arm64-v8a --max-workers=2 --console=plain
./android/gradlew -p control assembleRelease --max-workers=2 --console=plain
```

Corrected build PASS: 3m 9s, 33 executed / 474 up-to-date tasks. Control build PASS:
3m 12s, 48 executed tasks. APK signature verification, publish script syntax,
patch application and physical evidence assertions PASS. Original completed build
was reused first; rebuilding occurred only for the proven Activity/lifecycle fix.

Expo 57.0.25, expo-updates/brownfield 57.0.23, RN 0.86.3, React 19.2.3, Hermes
compiler 250829098.0.17; exact npm lock is included. Fixture revision is
`feb29fc4ac1f5eb011bdfe95391190a91da4bb31`, external checkout
`~/.dootah-v2/expo-custom-server`. This local fixture uses unsigned loopback HTTP
and outdated upstream dependencies. Production signing, server hardening, consumer
packaging, transparent entry hooks and the portable security boundary remain later
work. Default RN/Expo module reachability is broader than the intended capability
boundary. No EAS, React Native screen, Dootah annotations or V1 compiler are needed
for this spike. Consumer Gradle-only integration is not claimed yet.

The fixture and logcat capture were stopped after verification; ADB reverse was
removed and the app's networking is enabled. The device retains the corrected
APK with rollback active.

The Phase 1 record above is historical. The current sample uses the signed xprem
configuration and corrected embedded build inputs documented in the Phase 2C proof.
No runtime/renderer/health source changes were needed for Phase 2C.
