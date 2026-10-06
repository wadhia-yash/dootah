> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.
> [Current public security model](../security/README.md).

# Phase 6 security investigation

**Current status:** Phase 6 PASS on the accepted replacement APK. Separate native capability isolation, source-driven logic/UI OTA, negative tests, restart and signed rollback are proven. See [final acceptance](evidence/phase6-20260928/acceptance.json). The historical failures below remain preserved.

The investigation below is preserved as historical evidence; the continuation section describes the replacement architecture.


## Historical investigation before the replacement runtime

2026-09-28: **STOP — runtime isolation FAIL. Phase 6 is incomplete.**
The actual accepted Phase 4/5 APK ran a signed diagnostic OTA on Samsung SM-E426B.
It accessed undeclared RN and Expo modules, performed networking and filesystem
operations, and executed dynamic JavaScript. No valid Dootah render envelope was
sent. Native UI fallback remained intact but did not prevent these operations.
Business-logic expansion stopped at the requested architectural stop condition.
There is no new accepted baseline, secure runtime, or Phase 6 completion commit.

## Evidence and scope

- Branch starting point: `pivot/dootah-v2`, `c605709`.
- Accepted installed package: `dev.dootah.consumer`, version `0.4.0`, Android 13.
- Runtime: `dootah-v2-hook-1`; Hermes bytecode version 98.
- APK SHA-256: `778b3414da61bcabc1f1a3e043c46b70bfb4cd364b06764f303f31ac791c3068`.
- Last package update: `2026-09-27 19:47:26 IST`, unchanged during the investigation.
- Diagnostic release: `17905381715252`, UUID `1c1441a7-1e8b-2438-07a9-41fb2490adbc`.
- The host independently verified the manifest signature and downloaded asset hash;
  device logs also recorded signature verification and completed download.
- [Curated observations](evidence/phase6-20260928/exposure.json) contain global names,
  property descriptors, module availability, API names and harmless probe results.
  [Investigation record](evidence/phase6-20260928/investigation.json) binds these to
  publication, APK, device, fallback and cleanup observations.

This was handwritten **adversarial test code**, not a source-driven business-logic
acceptance artifact. Its imports were included by the same pinned Metro/Hermes
producer and accepted by the unchanged signed-update path. Tests against a Node VM
would not establish this Android exposure; the probe ran in the installed Hermes.

## Actual exposed surface

“Required internally” below means needed by existing trusted delivery/host machinery,
not permission to expose it to portable application code. “Remove” means from the
remote execution environment; installed native delivery can retain its own access.

| Surface | Observed exposure | Required internally | Required Phase 6 disposition |
| --- | --- | --- | --- |
| JS globals | Full Hermes/RN environment; complete observed name list in evidence | Some engine primitives | Retain only specified deterministic operations; current global set is not an allowlist |
| Native registry | `nativeModuleProxy`, `NativeModules[name]`, `TurboModuleRegistry.get(name)` all resolve installed modules | RN host infrastructure | Remove from remote environment |
| Bridgeless details | `nativeModuleProxy` is non-configurable and non-writable; `__turboModuleProxy` absent | RN bridging | Absence of the older proxy is not isolation |
| Expo registry | `expo.modules[name]`, `requireOptionalNativeModule`; `expo` non-configurable/non-writable | Expo host and brownfield bridge | Remove registry and discovery; replace remote bridge with installed typed capabilities |
| RN networking | `Networking`, `WebSocketModule`, `fetch`, XHR, WebSocket reachable | Native updater needs networking; portable text does not | No remote networking without a declared installed capability |
| Actual network operation | `fetch` to test-owned loopback server returned the marker | Not required for portable logic | Deny; loopback success proves a real network operation, not every endpoint/protocol |
| Filesystem | Modern `FileSystem` and legacy `ExponentFileSystem` reachable; cache marker write/read/delete succeeded | Native updater needs private storage | Deny remote filesystem; Android app sandbox is not a Dootah capability boundary |
| Blob/file APIs | RN `BlobModule`, `FileReaderModule`; JS `Blob`, `File`, `FileReader` | RN polyfills | Remove unless explicitly installed as a bounded capability |
| Timers/async | Timeout executed; interval, microtask, scheduling globals present | Host/event infrastructure | No uncontrolled remote scheduling |
| Time/random | `Date.now`, `Math.random`, `performance.now` callable | Host diagnostics/scheduling | Exclude from deterministic pure logic |
| Storage/state | Brownfield shared-state native module reachable; file persistence available; `localStorage`, `indexedDB`, probed AsyncStorage name absent | Brownfield library; no general state needed for current constant text | No generic shared state or storage; absence of one storage API is insufficient |
| Platform/device | PlatformConstants, DeviceInfo, AppState, Appearance available | RN initialization | Remove from remote scope unless explicitly declared |
| Android side effects | Clipboard, IntentAndroid, PermissionsAndroid, ShareModule, ToastAndroid, Vibration resolve through all three RN paths | Not needed for current Dootah text | Deny; only lookup was tested, no clipboard read, intent launch or permission request |
| Other Expo modules | Fetch, constants, updates, brownfield messaging/state, asset, fonts, keep-awake, DOM webview, EAS client reachable | Some are inherited dependencies, not Dootah requirements | Keep delivery/lifecycle native; remove general remote access |
| Module import | Bundled static imports, Metro `__r`/`__d`; bundled dynamic `import('react-native')` executed | Current Metro bundle bootstrap | No arbitrary imports in portable code; this does not claim network URL import or Node resolution |
| Dynamic code | Indirect `eval`, `new Function`, function-constructor access to native Networking executed | Not needed for portable logic | Deny at execution boundary, not by source lint alone |
| Other global/native bridges | Fabric UI manager, runtime scheduler, logging hook, RN exception/callable-module hooks, Hermes internals, segment hooks listed | RN engine/host machinery | Never expose them as portable APIs; not every hook was invoked |
| JVM/Android objects | Named Context, Activity, Java, Packages globals absent | Remain native | No direct generic reflection bridge demonstrated; absence does not repair the module escape paths |
| Uninstalled capabilities | Probed camera/location/contacts/Bluetooth and unknown module names returned absent | Not required | This is missing registration, not Dootah authorization; do not claim all aliases or native APIs exhaustively tested |

The only intended application capability in Phase 5 is constant native BasicText.
No broad module in this table is intentionally retained as an authorized portable
capability. The implementation currently exposes them anyway.

## Why the current embedding is the blocker

The inspected path is:

```text
Expo verifies/selects remote launch asset
  -> ExpoReactHostFactory's JSBundleLoader loads it as the ReactHost entry
  -> RN initializes engine globals, core packages, native module provider
  -> MainReactPackage + ExpoModulesPackage expose installed native modules
  -> downloaded Metro/Hermes bundle runs in that same runtime
  -> optionally sends a message to Dootah's render validator
```

[DootahApplication](../../v2/android-sdk/runtime/src/main/java/dev/dootah/runtime/DootahApplication.kt)
registers both packages. The local pinned `ExpoReactHostFactory.kt` chooses
`UpdatesController.launchAssetFile`; `ReactInstance.kt` also adds CoreReactPackage
independently of Dootah's supplied packages. The pinned C++ runtime installs native
bindings and timers before evaluating the bundle. Expo's host object resolves names
through its installed native registry, independently of Portable IR `requires`.

Upstream source confirms the two relevant embedding contracts:
[RN native module binding](https://github.com/facebook/react-native/blob/v0.86.3/packages/react-native/ReactCommon/react/nativemodule/core/ReactCommon/TurboModuleBinding.cpp)
and [Expo launch asset handoff](https://github.com/expo/expo/blob/sdk-57/packages/expo-updates/android/src/main/java/expo/modules/updates/UpdatesPackage.kt).
The conclusions here rely additionally on pinned local source and physical execution.

There is no trusted bootstrap followed by a separate untrusted realm. The downloaded
entry itself includes the bootstrap. A publisher credential holder can publish a
different bundle through the existing server API without calling Dootah's analyzer.
The tested readonly native globals cannot be removed by a generated preamble.
Lexical wrappers do not confine global access or function constructors. Deleting
`fetch` leaves native Networking and Expo fetch/filesystem paths. Removing just
MainReactPackage leaves Expo and automatically added RN core bindings. Render
validation happens too late to authorize JavaScript's earlier effects.

**The current shared RN/Expo execution architecture cannot provide the requested
boundary through generator, message-parser or JavaScript-only changes.** A different
native embedding/loading boundary is required before expanding logic. This is not a
claim that Hermes itself can never be embedded securely, nor a proof that a custom
native RN fork could never restrict all providers. Those would be new architectures
with their own baseline and acceptance burden, not an existing sandbox switch.

Candidate next designs to evaluate, not implemented guarantees:

1. Keep Expo verification/cache/rollback as trusted delivery, and execute portable
   code in a separately constructed Hermes runtime with only fixed typed Dootah
   host functions, native resource enforcement, and no RN/Expo bindings. Never also
   load the untrusted entry into the privileged ReactHost.
2. Deliver validated Portable IR as data to an installed bounded interpreter, with
   an immutable trusted bootstrap. The launch path must enforce data-only loading
   before any remote bytes execute; interpreting IR inside a replaceable OTA JS
   entry is not sufficient. This changes the current generated-JS execution model.

Either requires preserving Expo signature/hash verification, selection, recovery,
real frame health and rollback, and a new native baseline. An additional VM alone
does not establish bytecode robustness, time/memory limits or process containment.
Do not replace mature update machinery with a homegrown protocol to bypass this gate.

## Threat model

Assets include app-private data, device permissions, user-visible native UI, installed
contracts, trusted native code, update trust anchors, cache and availability. Treat
all remotely supplied program bytes and values as hostile even when authenticated.
The installed APK/OS and its trust configuration are the baseline trust boundary;
rooted-device compromise and native engine vulnerabilities need separate defenses.

| Threat | Existing protection and remaining gap |
| --- | --- |
| Malformed generated payload | Publisher and native render validator reject bounded malformed text envelopes; arbitrary JS executes before that parser |
| Compromised publisher credential | Server authentication no longer helps; an authorized publisher can upload a different executable bundle, as this probe demonstrates |
| Compromised server/signing path | Anyone able to issue trusted signatures can authorize executable updates; signing does not constrain effects |
| Malicious or unexpectedly permissive JS | Current host exposes networking, filesystem and modules even without render success |
| Capability escalation/unknown native operation | Unknown names return absent, but known installed names bypass Dootah declarations entirely |
| Stale contract/wrong capability version | Phase 5 local pins and runtime-version selection help publication compatibility; there is no installed runtime capability authorization check before JS execution |
| Replay/incompatible release | Expo retains signature, runtime/channel selection and update ordering/rollback behavior; this investigation adds no replay/fleet-attestation guarantee and does not claim signatures alone establish freshness |
| Arbitrary import/eval | Metro graph imports and Hermes dynamic evaluation are available; an attacker can ship their own graph |
| Unexpected network/file access | Actual operations succeeded with native UI still in fallback; Android permissions/sandbox bound the app, not remote Dootah code |
| CPU/memory exhaustion | No Dootah execution operation budget or preemption; render text limits and the health window do not interrupt an infinite JS loop |
| Exceptions/async/reentrancy | RN/Expo error recovery remains; it is not a typed capability boundary and cannot undo side effects |
| Rollback | Restores selected native/embedded behavior after delivery and restart; cannot retract leaked data or undo arbitrary filesystem effects; offline clients cannot receive new rollback immediately |

Signatures authenticate update/directive bytes under an installed key. Verified asset
hashes bind downloaded bytes to that manifest. They do **not** establish safety,
least privilege, determinism, absence of malicious business rules, or bounded work.
Transport signing succeeded in this experiment while isolation failed.

## Tests, restoration and reproduction

[Probe source](../../v2/android-sdk/tests/security/exposure-probe.js) performs a
loopback marker request and writes only its own temporary cache marker, deleting it
in `finally`. It does not read clipboard, launch intents, enumerate user files,
request permissions or execute an unbounded loop. Registry tests inspect availability
and names without claiming every native operation succeeds. It deliberately sends
no render envelope; the native screen stays visible and remote health is not accepted.

The eight [evidence tests](../../v2/android-sdk/tests/security/exposure.test.mjs)
assert that exposure really occurred. **Eight passing exposure tests mean the
security-negative gate FAILS**, not that prohibited access was blocked.

```sh
node --test v2/android-sdk/tests/security/exposure.test.mjs
DOOTAH_EXPOSURE_LOG=/tmp/dootah-v2-phase6/probe-active.log \
  node --test v2/android-sdk/tests/security/exposure.test.mjs
```

To reproduce on a controlled device, use a temporary producer workspace with the
locked `v2/runtime-spike/node_modules`, this probe as `index.js`, and the accepted
runtime/package configuration. Export Android with the existing Expo CLI; supply
`expoConfig.json` as in the publisher. Serve exactly `dootah-exposure-ok` at
`http://127.0.0.1:3101/dootah-exposure`, reverse test ports 3100/3101, and publish via
`v2/server/publish.mjs` with the existing private test credentials. Verify the signed
manifest and launch asset hash. Launch once to download, restart to activate, capture
logcat, and pass that capture to the test above. This intentionally demonstrates a
vulnerable host; it is not a normal publishing workflow or security certification.

The investigation issued a signed `rollBackToEmbedded`, delivered it, restarted and
observed the original UI and embedded UUID `421a1346-fe3b-4bbf-a04b-9b9093261d97`
with actual native-frame health accepted. A further restart with server stopped and
ADB reverse removed verifies restored native behavior. No reinstall, data clear,
client database edits, false health acknowledgement or signing bypass occurred.
The loopback server was stopped. Raw logs, APKs and exports remain in
`/tmp/dootah-v2-phase6`; no private material is included in curated evidence.

## Resume point

Resume **Phase 6**, at the native execution-boundary decision above. First demonstrate
on a new baseline that hostile signed remote bytes cannot reach RN/Expo registries,
network/files, dynamic evaluation or unbounded work. Then implement the installed
contract, typed dispatch ABI, deterministic logic and remaining source/device gates.
See [capabilities](CAPABILITIES.md) and [portable logic status](PORTABLE_LOGIC.md).
Phase 7 is not unlocked and has not begun. No Phase 6 completion commit is permitted.

## Phase 6 continuation: selected boundary

The investigation above is preserved historical evidence of the Phase 4/5 host.
The replacement under validation uses AndroidX JavaScriptEngine **1.0.0** and its
WebView-provided JavaScriptSandbox. `DootahApplication` no longer implements
ReactApplication, creates a ReactHost, registers RN/Expo packages, or evaluates a
remote launch asset through RN. Expo's native UpdatesController still performs
signature verification, download, cache selection and signed rollback. Its selected
launch file is now a bounded Portable IR JSON document, never a React entry.

### Options evaluated

| Option | Native boundary and resource implications | Decision |
| --- | --- | --- |
| A. Separate Hermes runtime using existing AAR/JSI | `makeHermesRuntime` can omit all RN/Expo bindings. The pinned AAR also exports `hardenedHermesRuntimeConfig`, eval controls, GC limits and async break controls. In-process fatal/OOM behavior still affects the application; untrusted precompiled bytecode and timeout coverage need care. A JNI adapter plus isolated Android service/IPC would be additional maintained code. | Viable alternative, more integration than the selected maintained process sandbox |
| B. AndroidX JavaScriptSandbox | Maintained Android API with a WebView-owned isolated process, bounded result and isolate heap controls, and termination support. No app native registry/object bridge is installed. Availability is feature checked; absence retains native behavior. | Selected and physically probed on Samsung |
| C. Other engine already in dependency graph | The pinned graph supplies Hermes and system WebView; no separately configured QuickJS/JSC engine or bridge is already integrated. A WebView page adds DOM/navigation/network machinery inappropriate for this boundary. | No smaller existing integration identified |
| D. Supported Hermes host-function-only embedding | The same standalone Hermes/JSI API as A; host functions are explicitly installed by the embedder, not inherently RN. It is not a special safe mode of ExpoReactHostFactory. | Valid embedding API, not selected for this Android implementation |

Sources: [AndroidX usage and crash behavior](https://developer.android.com/develop/ui/views/layout/webapps/jsengine),
[stable 1.0.0 release](https://developer.android.com/jetpack/androidx/releases/javascriptengine),
[Hermes embedding API](https://github.com/facebook/hermes/blob/main/API/hermes/hermes.h).
The Hermes assessment additionally inspected the **pinned**
`hermes-android:250829098.0.17` AAR headers, rather than assuming current upstream
features exist in the installed version. No JS VM was implemented or forked.

### Two distinct enforcement layers

1. **Native capability isolation:** `RestrictedSandbox` communicates only source and
   result strings with a fresh isolate. No host callback, Android/JVM object,
   arbitrary file descriptor, named data, module resolver or network capability is
   supplied. The external isolated process cannot discover the application's RN or
   Expo registries simply because their native libraries remain packaged.
2. **Deterministic portable semantics:** the APK's shared `PortableProgram` validator
   compiles only closed, typed IR. Publisher and runtime use the same Java source.
   The runtime never evaluates JS supplied by the publisher. Generated JS is retained
   as publishing evidence and independently regenerated inside the APK from validated
   IR. This rejects clocks, randomness, imports, eval, async, arbitrary loops and host
   invocations before evaluation. This is an additional restriction, not the sandbox.

The raw sandbox still has normal ECMAScript builtins, including eval, Function,
Date, Math.random, Promise, WebAssembly, reflection on JS objects, and the provider's
`android` data-consumption object. These are **not** Android Context/JVM reflection.
No named data is supplied. Dynamic evaluation of native registry lookups fails just
as direct lookup does. Dynamic import of react-native rejects. Portable Logic does
not expose any of these operations in its grammar. Language-level eval alone is not
reported as failure of native capability isolation.

Physical proof in the independent `dev.dootah.sandboxproof` diagnostic package used
exactly the runtime's Java sandbox adapter and shared IR compiler. It returned `80`
for typed inputs `100,20`; rejected direct RN/Expo/Context/JVM/network/file attempts;
terminated an infinite loop; rejected oversized results; and enforced the heap cap.
On WebView 153.0.8010.39, heap exhaustion terminates the whole sandbox process.
The application survives and a new sandbox works. Production rejects the override
on such an error rather than retrying the program indefinitely. This diagnostic
package is distinct from, and does not replace, the accepted consumer baseline.

### Residual risks and limits

The installed APK, Android/WebView isolation and engine are trusted components;
engine/OS vulnerabilities and rooted-device compromise remain outside this proof.
Keep WebView updated. Providers lacking required termination/heap features cannot
run an override. A heap cap is not a total RSS cap; process and engine overhead remain.
Expo's persisted-asset integrity/replay caveats from Phase 2C remain; this work does
not invent new cryptographic freshness guarantees. Authorized malicious business
rules can still produce incorrect allowed text/results; signing authenticates their
origin and the capability boundary limits their effects.

The native-only lifecycle adapter adds explicit monitoring and rejected-launch
accounting using Expo's existing database delegate. It is still a maintained,
version-pinned Expo patch. Expo's existing policy treats an update with a prior
successful launch as launchable even after a later failure. Every subsequent launch
still validates it and every failing invocation retains native behavior. No promise
of permanent quarantine after post-health input failure is added here.

## Dispatch regression and restoration (continuation)

The first ABI 2 release candidate (`fb96a573973a508f66e1299682858e2d40a2d189b7d7f883ef9f2e5eb8067549`)
passed native baseline and signed Kotlin discount OTA (`Discount: 10` -> `Discount: 20`)
with native health acknowledgement. Full UI inspection found a regression: the unrelated
member default-argument row became `Member explicit` rather than `Member`. A restart
reproduced it; retained Phase 5 screenshots did not show it. This candidate does not
pass Phase 6. Signed rollback restored the entire original native screen, with no
reinstall or data clear. Its original install timestamp remains unchanged.

The cause was the new `produceState` composable creating groups before the hooked
function's native body on an unresolved result. The fix moves bounded result caching
and worker evaluation into DootahApplication. `portableText` is now ReadOnlyComposable;
a miss creates no Compose groups, preserving Phase 4's proven invariant. A compiled
bytecode regression test enforces that invariant. Cache growth is bounded to 128
distinct invocations per process and at most 32 pending requests; overflow rejects
the override. The renderer remains the existing native BasicText/frame-health path.

A separate `dev.dootah.consumer.validation` APK/runtime
`dootah-v2-logic-2-dispatch-proof` demonstrated the fixed combined Kotlin OTA:
`Special discount: 20`, both original member rows, and a working native counter
after another recomposition. Its cached offline restart passed with package networking
disabled and ADB reverse removed, then network settings restored. It is diagnostic
evidence, not a substitute for final acceptance on the consumer release APK.

Replacement release candidate: SHA-256
`95c18eda921fd4f00b804ea8b7166f1e05d8db93df8f02b9a436f1704a9647f2`.
It builds through R8 and includes the group fix plus strict UTF-8/trailing-data
artifact rejection. After the user instructed continuation following the replacement request, this APK
was installed at `2026-09-28 19:32:40 IST` and final acceptance restarted. No further
reinstall or data clear is permitted during that sequence. The first candidate is
retained as failed evidence; it is not counted as the accepted baseline.


### Current threat controls

| Threat | ABI 2 enforcement / limit |
| --- | --- |
| Malformed or malicious signed artifact | Bounded strict UTF-8 input, JSON structure/field/type/ABI/capability validation before any execution; raw JS is rejected as data |
| Compromised publisher credential or signing server | Can issue authorized business rules within the installed grammar; cannot install native modules or bypass native IR validation/process isolation |
| Arbitrary imports/native discovery | No ReactHost or RN/Expo registry attached; no module loader or host callbacks supplied to the separate sandbox |
| Unexpected networking/filesystem/JVM access | No such capability installed; raw sandbox attempts fail independently of the generator |
| Stale runtime or input contract | Expo runtime selection plus native runtime/logic ABI checks and exact installed hook types; reject to native fallback |
| Resource exhaustion | Payload/parser/expression/operation/input/result bounds plus isolate heap cap and deadline; sandbox death rejects the release |
| Replay or cached-asset mutation | Existing Expo signature/runtime/cache policy remains; signatures do not promise freshness. Native validation and isolation also apply to cached artifacts |
| Incorrect but permitted business result | Outside authentication/isolation guarantees; publisher review and signed rollback remain necessary |

Signing authenticates the delivery. It neither proves business correctness nor grants
capabilities. The maintained sandbox is an independent boundary even if a bug in the
closed compiler were to emit more JavaScript than intended. Resource containment is
not a proof against Android/WebView vulnerabilities or all forms of denial of service.


## Final accepted Phase 6 baseline and device proof

2026-09-28: **Phase 6 PASS** on Samsung SM-E426B, Android 13. Accepted R8 APK
SHA-256: `95c18eda921fd4f00b804ea8b7166f1e05d8db93df8f02b9a436f1704a9647f2`.
Package install timestamp before/after: `2026-09-28 19:32:40 IST`.
Embedded update: `9f64f236-92d9-4ac1-8845-9a3d71583710`.
After the authorized replacement, there was **no reinstall or data clear** through
this entire successful sequence. The initial failed candidate is documented above.

[Curated acceptance](evidence/phase6-20260928/acceptance.json) records APK hashes,
installation timestamps, release identities/hashes, signature checks, UI observations,
health acknowledgements and rejection reasons. Raw device logs, screenshots, APKs,
exports and credentials remain outside git. A call briefly obscured one UI capture;
that case was repeated successfully and the obscured capture is not acceptance proof.

| Gate | Physical result |
| --- | --- |
| Native baseline | `Discount: 10`; all original native rows; frame health PASS |
| Kotlin business edit | Local helper `p * 10 / 100` -> `p * 20 / 100`; `Discount: 20`; health PASS |
| Combined Kotlin UI/logic | Same signed release changes rule and label to `Special discount: 20`; health PASS |
| Recomposition | Native counter advances to 1; original `Member` and `Member explicit` rows preserved |
| Online / offline restart | Combined cached result and frame health PASS; offline app networking disabled and ADB reverse removed, then settings restored |
| Capability/type/ABI/resource negatives | 12 signed adversarial artifacts rejected; original native text retained; no remote health acknowledgement |
| Invalid signature | Tampered manifest and directive rejected by Expo code-signing verifier; safe cached/native behavior retained |
| Recovery / signed rollback | Healthy source-generated combined release restored; authenticated rollback restores `Discount: 10` and original label/rows; native health PASS online and offline |
| APK identity | SHA-256 and install timestamp identical before/after acceptance |

The 12 signed negatives cover missing, unknown and wrong-version capabilities;
unknown native operation; runtime ABI mismatch; malformed portable value; incompatible
installed input type; expanded-operation overflow; raw RN, Expo and dynamic-JS
payloads; and trailing executable text. Each was accepted by delivery authentication
and rejected by the native portable validator. Separate raw-engine probes
([sandbox evidence](evidence/phase6-20260928/sandbox-proof.json)) independently establish
that native registries, filesystem, networking, Context and JVM access are absent
inside the engine. The accepted consumer also runs the engine under a distinct
isolated UID (`u0_i9235`, versus application `u0_a700` in the captured process sample).

Publisher tests: 18 PASS; real Compose instrumentation tests: 6 PASS; runtime tests:
2 PASS; native health adapter tests: 3 PASS. The valid but unsupported Kotlin edit
`val unsupportedClock = System.nanoTime()` passes normal Debug/D8 and Release/R8
builds while Dootah publication refuses it. The edit was removed. No compiler plugin
or FIR/IR mutation was introduced. Acceptance publications used Kotlin source and
automatic function identities; handwritten JS was limited to adversarial probes.

Approximate Samsung measurements using the production sandbox adapter: fresh-isolate
bridge round trip 9.40 ms; validation/compilation 0.368 ms; combined path 10.88 ms.
Earlier consumer sampling measured PSS 39,033 KiB native versus 40,111 KiB portable,
plus 16,572 KiB sandbox PSS (85,040 KiB RSS). These are separate rough samples, not
steady-state benchmarks or total-memory guarantees. The heap cap is 8 MiB, not an
RSS cap. Timeout, oversized result and heap exhaustion terminate/reject work; the
application survives and a fresh sandbox can recover.

## Exact Phase 7 starting point

Phase 6 is complete; Phase 7 has not started. Start from the committed ABI 2 SDK
(`0.6.0-local`), Logic ABI 1, the independent source publisher and the accepted
`dootah-v2-logic-2` baseline described here. The device is restored to native baseline
by signed rollback. Keep the restricted execution boundary, typed contracts, budgets,
original method fallback and build/publishing separation. Phase 7 scope has not been
specified: define the next capability or grammar increment and its acceptance gates
before implementation. Any new native capability requires a versioned contract and
new APK; it must not reintroduce shared ReactHost execution or generic native lookup.


## Phase 7 preservation

[Cloud architecture](CLOUD_ARCHITECTURE.md) records the completed-phase continuation
and the explicit authorization for a minimal telemetry SDK/new baseline. Portable
grammar, Runtime ABI 2 / Logic ABI 1, `RestrictedSandbox`, `PortableProgram`, the
dispatch visitor and Expo signing/health patch are unchanged. Telemetry networking
is trusted native SDK code with no portable callback/module bridge. Signed Cloud
updates and native restoration are independently verified in the Phase 7 evidence;
prior Phase 6 adversarial matrices remain applicable to the unchanged boundary.
