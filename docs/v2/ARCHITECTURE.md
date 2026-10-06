> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# Dootah V2 architecture

Decision date: 2026-09-26. Phase 0: PASS for technical plausibility only.
No V2 device, runtime, packaging, compiler-hook or release acceptance is implied.

## Product boundary

Dootah is for native Android developers writing ordinary Kotlin and Jetpack Compose.
There is no React Native migration, JSX screen, Expo project, EAS account, navigation
restructure, or per-screen `@Dootah` / `@Bundlable` annotation requirement. Expo,
React Native, Hermes, Metro and internal JavaScript are Dootah implementation details.
Consumer integration ultimately consists of normal Gradle/Kotlin configuration.

Dootah owns its SaaS/control plane: customers, organizations, apps, channels, releases,
rollouts, pause/resume, rollback, kill, installation/adoption data and eventually billing.
The production update origin is `https://updates.dootah.dev/...`, not EAS or an EAS
proxy. This document does not claim that origin is deployed.

V1 remains historical working evidence. V2 does not rewrite V1 in place or depend on
V1's updater or transforming compiler plugin. Unsupported surfaces remain native.
New native libraries, permissions, manifest requirements and capabilities require an APK.

## Selected path

```text
Ordinary Kotlin/Compose -> normal Kotlin/Compose compilation -> valid JVM bytecode
                                                           -> native APK
                                                              |
                         later: small optional entry dispatch-+
                                                              |
Self-hosted Expo protocol server -> expo-updates -> RN/Hermes -> typed Dootah messages
                                                              |
                                                    native Compose state/rendering

Separate publish command -> read-only whole-screen analyzer -> Dootah Portable IR
                         -> generated JS + Expo export assets -> self-hosted server
```

Use the SDK 57 dependency family described in DEPENDENCIES.md. Use Expo's own
custom-server example for the bounded Phase 1 local proof. Select xprem's MIT core
as the Phase 2 server foundation. [SERVER_FOUNDATION.md](SERVER_FOUNDATION.md)
locks the Phase 2A selection and documents the required MIT-only wiring adaptation;
the default upstream build includes commercial code. Actual protocol behavior
is proved host-side in [SELF_HOSTED_SERVER.md](SELF_HOSTED_SERVER.md); device behavior
remains a Phase 2C gate. Do not implement a replacement protocol/cache/signer.

The hidden runtime is a ReactHost running Hermes with **no React surface**. Kotlin
owns the Activity and `setContent`; `ExampleScreen(title)` calls real Compose `Text`.
The initial JS entry sends a small validated title message through brownfield messaging.
Register the native listener before starting JS; deliver state changes on the UI thread.
The initial spike is explicit runtime wiring, not transparent source extraction.

## Phase 0 evidence chain

Each link below is supported by upstream APIs/source. Their combination is an
engineering inference to test in Phase 1, not an already demonstrated integration.

| Link | Evidence | Implication |
| --- | --- | --- |
| Native app consumes internal runtime | [Isolated brownfield Android integration](https://docs.expo.dev/brownfield/isolated-approach/) | Dootah can produce Maven/AAR artifacts; consumer tooling can remain Android-only. |
| Custom server without EAS | [Updates API/configuration](https://docs.expo.dev/versions/latest/sdk/updates/) and [protocol v1](https://docs.expo.dev/technical-specs/expo-updates-1/) | Configure update URL and runtime version in native configuration; EAS is one possible service, not required. |
| Downloaded/cached bundle becomes JS entry | [SDK 57 UpdatesPackage](https://github.com/expo/expo/blob/sdk-57/packages/expo-updates/android/src/main/java/expo/modules/updates/UpdatesPackage.kt) | Expo host handlers initialize the controller and return its selected launch asset. |
| Start JS without rendering RN UI | [RN 0.86.3 ReactHost](https://github.com/facebook/react-native/blob/v0.86.3/packages/react-native/ReactAndroid/src/main/java/com/facebook/react/ReactHost.kt) | `start()` initializes the instance separately from creating a surface. |
| Brownfield host uses Expo handlers | [SDK 57 host template](https://github.com/expo/expo/blob/sdk-57/packages/expo-brownfield/plugin/templates/android/ReactNativeHostManager.kt) | The supplied host factory is `ExpoReactHostFactory`; preserve its update integration. |
| JS talks to Kotlin | [Brownfield communication API](https://docs.expo.dev/versions/latest/sdk/brownfield/) | JS `sendMessage` and Android `BrownfieldMessaging.addListener` provide the bridge. |
| Mature JS engine | [Bundled Hermes](https://reactnative.dev/docs/hermes) | Use RN's matching Hermes distribution, not an independently chosen engine ABI. |
| Own endpoint implementation exists | [Expo example](https://github.com/expo/custom-expo-updates-server), [xprem](https://github.com/mercuretechnologies/xprem) | Reuse existing server code; no EAS account is on the delivery path. |

This satisfies the Phase 0 plausibility gate: native app -> self-hosted endpoint ->
downloaded JS -> existing JS engine -> native Android communication. It does not prove
headless launch/reload/error recovery or the install-once gate.

## Runtime and capability responsibilities

Expo owns manifest parsing, signature verification, assets, durable update database,
selection, launch and built-in recovery. Retain its safety checks. Dootah owns message
validation, semantic compatibility, native rendering, and an explicit installed
capability registry. Portable IR belongs to Dootah and has no Expo or RN UI node types.

Runtime compatibility must identify the installed bridge/Portable IR version, supported
capabilities and engine/bundle compatibility. A package name alone is insufficient.
Reject incompatible or malformed trees and use intact native rendering. Bind UI and
business rules to the same release so rollback cannot mix generations.

An RN host is **not a security sandbox** merely because it is hidden. Before production,
inventory native modules and JS globals reachable from OTA, and constrain exposed
capabilities. The portable subset must not receive Context, reflection, arbitrary JVM
objects, filesystem access or Android APIs. Broad default RN/Expo modules are a spike
convenience, not proof of the Phase 6 capability boundary.

Rollback to an older remote payload should republish it as a newer update using the
server's supported rollback mechanism. Merely returning an older creation timestamp
does not establish that a cached newer update is displaced. Test rollback-to-embedded
directives separately if used. Pause only stops further distribution; kill must also
invalidate an already active release when a device next contacts the service. An
offline device cannot receive a newly issued kill command immediately.

## Unresolved engineering gates

1. Phase 1: run release-mode JS without RN UI and preserve Expo's launch/reload path.
   Expo's usual Activity and content-appearance recovery hooks assume RN rendering;
   determine how headless execution reports health without faking a rendered RN frame.
   Do not bypass recovery or manufacture a success receipt to make the spike pass.
2. Phase 1: actual device baseline, OTA, online/offline restart and rollback; measure
   APK bytes, cold startup and process PSS against a Compose-only control.
3. Phase 2: build xprem community core excluding every `ee/` directory; validate signing,
   channels, runtime selection and rollback against the same device. Its marketing
   feature list includes commercial features and is not the community entitlement list.
4. Phase 3: publish complete Android artifacts including required transitive metadata,
   assets, native libraries and manifest configuration; prove a fresh consumer needs
   no Node/npm/Metro/Expo configuration. An AAR alone is not automatically self-contained.
5. Phase 4: inspect compiled Compose methods before adding the smallest entry branch
   through official AGP instrumentation. Preserve descriptors, receivers, masks and
   original bodies; test D8/R8 and representative generated method forms on-device.
6. Phase 5: separate read-only publishing analysis from Android compilation. Analyzer
   errors must never fail `assembleDebug` or `assembleRelease`. No arbitrary IR
   copying, reparenting or body mutation, and no new compiler-version adapter matrix.
7. Phases 6–9: deterministic logic, constrained capabilities, Cloud controls, frozen
   multi-app acceptance and a public README-only install/publish/rollback exercise.

No later phase may proceed until the preceding gate passes. Never commit incomplete
device phases. No automatic merge or release.

## Repository preservation

Pre-V2 HEAD: `5784cb4` (`feat: complete Kotlin compiler compatibility integration`).
`archive/dootah-v1-pre-v2` and the annotated tag `archive/dootah-v1-final` point to that
exact commit. Uncommitted V1 work-in-progress from the original V1 checkout was archived
privately; it is not part of the public repository, the archive refs or V2 commits.
V2 work ran on `pivot/dootah-v2` in a separate worktree. The V1 product has since been
removed from the V2 tree; see [history](../history/README.md).
