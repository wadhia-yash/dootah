> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# Phase 2C — signed xprem physical-device proof

2026-09-27. **PASS on physical Samsung SM-E426B.** Phase 3 has not started.
Repository started clean on `pivot/dootah-v2` at `82c939e`.

## Configuration delta from Phase 1

| Setting | Phase 1 fixture | Phase 2C baseline |
| --- | --- | --- |
| Update URL | `http://127.0.0.1:3000/api/manifest` | `http://127.0.0.1:3100/manifest` |
| Runtime | `dootah-v2-spike-1` | unchanged |
| Package / version | `dev.dootah.v2.spike`, 1 / 0.0.0 | unchanged |
| App identity header | absent | `expo-app-id: 34e81d97-661f-4f3b-ad2c-8d4e7a6546a6` |
| Channel header | absent | `expo-channel-name: development` |
| Branch | fixture directory | server maps development channel to development branch; no branch override |
| Signing | unsigned; no client certificate | provisioned public X.509 certificate, `keyid=main`, `alg=rsa-v1_5-sha256` |
| Launch policy | ALWAYS / wait 0 | unchanged; downloaded updates activate on next process launch |

Expo supplies Android platform, runtime, protocol, current/embedded update IDs and
signature expectation headers. This is a self-hosted app identity, with no EAS
project ID. The public certificate is copied from existing `server-state/credentials.json`
to `v2/runtime-spike/xprem-public-certificate.pem` for Expo export configuration and
embedded in Android manifest metadata for native verification. No API credential,
private signing key, or master key enters the APK or repository. Unsigned manifests
remain disallowed and response-provided certificate chains remain disabled.

The native URL/header/certificate change requires one replacement baseline APK.
Changing the URL also changes Expo's default update scope; old fixture cache is not
proof of xprem acceptance. No data clear is needed. Retain the original runtime,
Compose renderer, Hermes bridge and version-pinned native-renderer health adapter:
validated message -> StateFlow -> hardware Compose frame commit -> launched-ID check
-> Expo recovery's own success acknowledgement. No React surface is created.

## Preserved backend

Started the existing final binary as `dootah-v2-server-phase2c` on `dootah-v2-net`,
using exactly the documented env/mounts/image and port 3100. `/ready` returned 200.
A signed selection request reached the existing PostgreSQL app/channel state and
returned the preserved Phase 2B rollback directive. Fetching the existing CAS asset
returned 1,433,590 bytes with SHA-256
`5c5bea1ab927e96030370f15047f4d4d39c861e06189afdadc1ae1cfd97616d2`.
No provisioning, new app, key generation, database reset or Phase 2B rerun occurred.

## Baseline artifact

SHA-256: `559d25be23abc35b09c4258d5693bb882031b83e158b6f03668ebd3ab476286a`.
JDK 21, SDK 36, arm64 release build; 507 tasks, 28 executed, 479 up-to-date.
The initial build invocation lacked ANDROID_HOME and failed before compilation;
setting the existing SDK path resolved it. No failed APK was installed.

The first attempt below failed; the approved corrected baseline and all accepted
results follow. The final fixed baseline hash is `dd91ee94516c8cbb46437d4afff3f52b1062dcfa60ddc99b65fbfc7472b28539`.

## Initial baseline failure and prepared correction

Installed once at **2026-09-27 08:47:04 IST**, retaining application data.
The installed base.apk hash matches `559d25be...6286a`. No OTA was published.
The first launch and a force-stop/restart both showed real Compose baseline text,
but Expo's launcher failed with `No launchable update was found` and Hermes bridge
reported `update=null`. Native health acknowledgement correctly did not fire.
This is **FAIL**, not a successful baseline or a crash-free acceptance claim.

The APK still contained Phase 1 embedded UUID
`abbbd47f-a56c-46e8-a121-f3f1d4b4ca3a`, commit time 1790415866006.
Build output marked `createReleaseUpdatesResources UP-TO-DATE` even though native
URL and request headers changed. The pinned Expo Gradle task declares path strings
as inputs, without tracking those configuration files. On device, Expo logged a
same-ID/different-scope warning. Source inspection explains the failure: Loader
updates the existing row's scope but preserves its old URL/request headers;
LauncherSelectionPolicyFilterAware rejects rows whose URL/headers differ from
current configuration. The initial package update retained the old cache, exposing
this integration issue. No client database was inspected or modified manually.

A small build wiring correction adds explicit file inputs to Expo's existing
UpdatesResources task; it does not replace resource generation or alter recovery.
Corrected candidate built successfully (507 tasks, 15 executed, 492 up-to-date),
and APK signature verification passed. It contains fresh embedded UUID
`d2569e0c-516e-4da4-826d-e6e759b2164a`, commit time 1790479159643.
Its embedded Hermes bundle is byte-identical to the installed attempt.
Candidate SHA-256:
`dd91ee94516c8cbb46437d4afff3f52b1062dcfa60ddc99b65fbfc7472b28539`.
The user explicitly approved one corrected baseline replacement before installation.
It was installed at **2026-09-27 08:51:03 IST** without clearing data. This is the
fixed baseline for the successful sequence; no further replacement is permitted. No signing/recovery bypass, data clear,
client DB mutation, new app or backend state reset was performed.

Public certificate SHA-256 fingerprint:
`C4728A1E1E4CC23557AFD8426789F87BA93EE189222ADFE8234E0859C7208475`.
Raw logs, screenshots, exports and candidate receipts remain outside Git in
`/tmp/dootah-v2-phase2c`. OTA #1 export was prepared but not published.

The corrected baseline passed: UI hierarchy contains `Dootah Native Baseline`,
Hermes bridge reports the fresh embedded UUID, the hardware Compose frame commits,
and Expo logs `Renderer launch acknowledged` for the same UUID. The retained
Phase 2B rollback predates this new embedded build and is correctly not applied.
Installed base.apk hash matches the candidate hash above.

## Targeted fault injection (same corrected APK)

Before allowing OTA #1's first download, a temporary host transport proxy on port
3101 forwarded to the real backend on 3100. ADB reverse routed the unchanged
client URL to that proxy. It never signed or generated a manifest, and held no
publisher credentials or private key. Successful delivery uses direct ADB reverse
to port 3100. Fault modes were narrowly bounded:

- Replace the outgoing runtime header with `not-installed`: xprem returns no update;
  Android remains on the healthy embedded baseline.
- Replace the outgoing app header with an unknown UUID: xprem returns 404; Android
  remains on the healthy embedded baseline. This tests server selection from an
  actual Android request, not a second app installation or tenant authorization.
- Flip one byte of the multipart manifest signature: Android logs
  `Code signing verification failed for manifest` / `UpdateCodeSigningError` and
  does not download or execute the rejected update.
- Preserve the signed manifest and flip one byte of the downloaded launch asset:
  Android first logs successful signature verification, then `AssetsFailedToLoad`
  from `UpdatesUtils.verifySHA256AndWriteToFile` at the hash-mismatch throw.
  The baseline still renders and receives Expo's renderer acknowledgement.

The corruption probe exposed an availability limitation: the proxy retained
xprem's `Cache-Control: public, max-age=31536000` on the corrupted response.
After direct transport was restored, a retry still failed hash verification,
consistent with Expo/OkHttp retaining the bad HTTP-cache entry. Safety held; the
bad bundle never executed. A **new signed release with a fresh exported asset
hash** recovered through the ordinary publisher and client update path, without
clearing cache/data or changing the APK. Republishing identical bytes under a new
update ID would not necessarily recover that asset URL. Document this as a
production recovery risk; this task does not patch Expo's HTTP cache or invent a
cache/protocol replacement. TLS remains required for production.

Probe release: integer ID `17904793199202`, UUID
`c2408b61-a0fc-f367-301b-d71881041191`, asset SHA-256 base64url
`UdipGsgtaDXEIQicbV_NnIhQDCEeV9E7qGLh3O80p_k`. It was never activated.
The successful OTA #1 release is recorded separately below.

An initial direct retry coincided with the device auto-locking and timed out;
its UI capture was the lock screen, so it is not accepted as UI evidence. The
screen was awakened and its timeout temporarily increased from 300000 to 1800000
milliseconds for subsequent captures. No application state was reset.

A preliminary OTA #2 Metro export retained OTA #1's environment-variable title
because of host transform caching. It was not published. `expo export --clear`
produced the correct OTA #2 title, verified in the Hermes artifact. This clears
host bundler cache only. Reproduction commands now require it for title probes.

## Accepted remote releases

| Release | xprem integer ID | Expo UUID | SHA-256 base64url | Bytes |
| --- | --- | --- | --- | --- |
| OTA #1 | 17904795930992 | a8907975-1861-8fdd-29b8-0bf6131b3136 | szSq84eTy53Qj3PF0wtvVVaSW2ShtjyRvl1UJ29Bdbg | 1433598 |
| OTA #2 | 17904797280162 | 8b1e67eb-9e15-4eb9-df51-d3fb28c766f9 | jiUdNrO3Lv34drks0m3jL9PEFfsWWeZkxdQ52q4W7s0 | 1433594 |

Both were published through the unchanged Phase 2B `v2/server/publish.mjs` adapter.
Host inspection independently checked exact manifest signatures and downloaded
SHA-256 values. Android logged `Manifest code signing signature verified successfully`
and `DownloadComplete` for each accepted release. The unchanged Expo download path
checks the advertised hash before promoting its temporary file; the corruption
probe exercised the failure at that exact check on the same APK.
Hermes delivered each distinct title through BrownfieldMessaging, native Compose
rendered it, and Expo acknowledged its committed frame under the matching UUID.
OTA #1 passed separate online and offline cached restarts. OTA #2 passed a separate
online restart. Offline testing disabled networking only for the spike package
and removed port 3100 ADB reverse; logs recorded a connection failure while the
cached update still rendered and acknowledged health. Networking was restored.

Backend rollback uses xprem's authenticated POST
`/{appId}/rollback/development?runtimeVersion=dootah-v2-spike-1&platform=android`.
It created rollback ID `17904798064702`, with signed directive
`rollBackToEmbedded` and commit time `2026-09-27T03:30:06.471Z`.
Before accepting it, a signature-byte flip produced Android's
`Code signing verification failed for directive`; OTA #2 remained healthy.
The same genuine directive was then delivered directly from xprem. No client
rollback implementation, client database mutation or native configuration change
was used.

## Boundaries and remaining risks

No EAS account, token, EAS API, hosted Expo update origin, React Native screen,
React surface or JSX UI was required. The SDK sends a locally generated
`eas-client-id` installation header to xprem; its name does not imply an EAS
network dependency. Native Activity/Compose and `index.js` remained unchanged.
The backend, publisher and manifest/asset origins in this run were local. Phase 2B
already established backend operation without egress; that proof was not rerun.
This is dependency/path evidence, not a full device-wide packet-capture audit.

Known risks retained from the spike: local HTTP requires production TLS; the
native-renderer health API is a pinned Dootah patch rather than an upstream API;
module/capability restrictions and consumer packaging are still later work.
The Gradle input correction covers this sample's current entry/configuration
files, not a general consumer dependency graph. The HTTP-cache corruption
availability limitation and host Metro title cache behavior are described above.
No fundamental xprem/SDK 57 signing or protocol incompatibility was found.

Phase 3 starting point (not begun): package the proven hidden runtime into Android
artifacts with complete transitive dependencies, manifest configuration, assets
and native libraries; validate a fresh Gradle/Kotlin consumer requiring no Node,
npm, Metro or Expo configuration. Keep the xprem delivery path and native health
adapter. Do not introduce compiler hooks or portable analysis in Phase 2C.

## Final acceptance and validation

**All Phase 2C gates passed.** Signed endpoint, Android signature verification,
asset integrity, stable embedded baseline, OTA #1, online restart, offline cached
restart, OTA #2, backend rollback, native Compose rendering, native committed-frame
health acknowledgement, wrong runtime/app selection, invalid manifest/directive
signature rejection, and corrupt asset rejection passed on the same corrected APK.
Rollback restored the embedded UUID and baseline title; both its online and offline
restarts remained healthy. Network failures left cached/native UI usable.

Final installed APK SHA-256, package path, version and `lastUpdateTime` match the
corrected baseline exactly. **No reinstall, replacement, data clear, native config
change or manual client database mutation occurred after 08:51:03 IST.** The
previous failed baseline and user-approved replacement are disclosed above.

Durable evidence: [sanitized acceptance record](evidence/phase2c-20260927/acceptance.json),
[baseline](evidence/phase2c-20260927/corrected-baseline.png),
[OTA #1](evidence/phase2c-20260927/ota1-active.png),
[OTA #1 offline](evidence/phase2c-20260927/ota1-offline.png),
[OTA #2](evidence/phase2c-20260927/ota2-active.png),
[rollback](evidence/phase2c-20260927/rollback-active.png),
[rollback offline](evidence/phase2c-20260927/rollback-offline.png).
The acceptance record contains selected event messages and identities, not raw
logs or publisher receipts. Raw artifacts remain outside Git.

Validation: corrected release build and APK signing passed; embedded UUID freshness
and unchanged embedded Hermes bytes were checked; native and Expo export URL,
headers and public certificate agree; all 14 physical stage assertions checked UI
text, Hermes ID/value, native frame acceptance, Expo renderer acknowledgement,
unchanged install timestamp and absence of fatal exceptions. Both successful
remote downloads and negative signature/hash checks were asserted separately.
No Kotlin/compiler/runtime/backend behavior changed, so completed Phase 1/2B
expensive matrices and unrelated V1 tests were not rerun.

Cleanup restored the original five-minute screen timeout and package networking.
ADB reverse is removed. The fault proxy and Phase 2C server are stopped;
PostgreSQL and persistent app/assets/rollback state remain intact. Restart with
the documented command and a fresh container name, or start the existing
`dootah-v2-server-phase2c` container. Phase 2 is complete; Phase 3 was not begun.
