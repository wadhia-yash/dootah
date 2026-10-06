> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# Compose entry instrumentation — Phase 4

2026-09-27: **Phase 4 PASS** on Samsung SM-E426B using one accepted **R8-minified**
APK for native fallback, automatic signed OTA routing, isolation, offline restart
and rollback. No per-screen Dootah annotation or manual dispatch is required.
This historical record covers Phase 4. The subsequent bounded source publisher is
documented in [Portable IR V1](PORTABLE_IR.md) and [publishing](PUBLISHING.md).

## Inspected compiler output

The inputs are `v2/native-consumer/app/src/main/java/dev/dootah/consumer/ComposeForms.kt`.
Normal Kotlin/Compose 2.1.20 compilation with AGP 8.12.0 produced these forms,
inspected with `javap -p -c -v` before instrumentation. Here `C` abbreviates
`Landroidx/compose/runtime/Composer;`; these are descriptors, not source guesses.

| Form | JVM descriptor / observation |
| --- | --- |
| Top-level Unit | `(CI)V`, public static final |
| String / Int overloads | `(Ljava/lang/String;CI)V` / `(ICI)V` |
| Nullable String with default | `(Ljava/lang/String;CII)V`; changed then default mask |
| String extension | `(Ljava/lang/String;CI)V`; receiver occupies first argument |
| Generic | `(Ljava/lang/Object;CI)V`; generic signature retains T |
| Member with default | `(Ljava/lang/String;CII)V`; implicit receiver in local slot 0 |
| Generic interface implementation | concrete String method plus synthetic bridge taking Object |
| Private / internal / public | private stays private; internal top-level is JVM public; metadata retains Kotlin visibility |
| remember/state and lambdas | original method owns restart group and state; generated restart helpers return `Lkotlin/Unit;` |
| Value-returning composable | `(CI)Ljava/lang/String;`, excluded |
| Ordinary function | `(I)I`, excluded |

`@Composable` is runtime-invisible on actual composable methods. Kotlin metadata
contains their already-lowered JVM signatures. The inspected defaulted composables
have masks on the original method, not a separate `$default` entry. Restart lambda,
accessor and synthetic bridge methods are not independent entrypoints.

## Selection and identity

Only application project classes are visited. Read-only Kotlin metadata matches
declared functions to exact method name/descriptor, then the visitor requires the
compiled Composable annotation and a verified Unit-returning shape. No source-name
heuristics select methods. The compiled `ComposableTarget` must identify the Android
UI applier (`androidx.compose.ui.UiComposable`); unknown/custom appliers and
`ReadOnlyComposable` methods remain native. Synthetic/bridge/abstract/native/synchronized methods,
inline/suspend/generic declarations, context receivers, value returns, unsupported
metadata and unrecognized Composer/mask layouts remain native. The initial bound
is nine explicit source arguments and one changed/default mask. Primitive and
String arguments, including nullable String and String extensions, are recognized.
Other object parameters are unsupported. Instance receivers remain local.

No values or receivers are serialized. This phase's only remote operation is
constant native text replacement. Nonzero default masks and nulls for non-null
String parameters branch to the original body, retaining default evaluation and
the original null-check exception behavior.

The shared pure Java `dev.dootah.identity.FunctionIdentity` model produces
`dth1:` + lowercase SHA-256 of UTF-8 newline-separated fields:

1. `dootah-entry-v1`
2. JVM internal owner name
3. JVM method name
4. full compiled descriptor
5. `static` or `instance`
6. `extension` or `ordinary`
7. generic JVM signature, or empty string
8. source argument nullability (`?`/`!`, extension receiver first)

Identity is generated before shrinking; R8 renaming does not change embedded ID
constants. Body edits do not affect identity. Moving owners, changing JVM ABI,
nullability or internal name mangling changes it. This is an installed JVM contract,
not a promise of identity stability across arbitrary Compose ABI changes. Phase 5
must reuse this model and validate against the installed artifact's identities.

## AGP integration and preservation

The plugin registers `AsmClassVisitorFactory` through `onVariants(selector().all())`
and `variant.instrumentation.transformClassesWith(..., PROJECT)`. AGP owns task
ordering, incremental inputs and frame recomputation with
`COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS`. No Kotlin task-name hooks, source rewriting,
compiler plugin, FIR/IR mutation or Compose compiler adapter is introduced.

The visitor adds only guards and an entry call/branch/RETURN. It neither clones nor
moves the body, writes argument locals nor modifies descriptors, annotations,
visibility, exception handlers or Kotlin metadata. An existing exact dispatch call
prevents stacking the prefix. Byte offsets/stack frames necessarily change when
the prefix is encoded; original instructions and branch relationships remain.

Official APIs: [AGP instrumentation](https://developer.android.com/reference/tools/gradle-api/8.12/com/android/build/api/variant/Instrumentation),
[Kotlin metadata JVM](https://kotlinlang.org/docs/metadata-jvm.html).

AGP's annotation summary deliberately omits `kotlin.Metadata`. An initial summary
filter silently selected no classes; direct inspection caught this before any OTA
publication. The corrected factory visits project classes and the method visitor
reads metadata from their bytes. The initial diagnostic APK is not an accepted
instrumented baseline. The no-hook D8/R8 builds do not establish hook acceptance.

## Runtime dispatch and health

The prefix calls the SDK's fixed Java `ComposeEntry.tryRender(String, Composer, int)`
ABI. Its lookup calls a normally compiled `@ReadOnlyComposable` accessor: it reads
the current native view's Application and a Compose snapshot value, creating no
Compose groups. A missing runtime or missing ID returns false. Only a match calls
the SDK's normally compiled `renderOverride`, which renders `BasicText` and attaches
the existing native frame modifier. No consumer receiver/argument crosses into JS.
The native path enters its unchanged compiler-generated groups with unchanged masks.
Snapshot observation causes the enclosing composition to see initial Hermes readiness.

The signed Hermes entry sends `dootah.dispatch.v1`, ABI 1, and a list of
`{functionId, title}` entries. Unknown fields, wrong ABI, duplicate IDs, non-string
values, malformed IDs, empty/oversized lists and oversized titles reject the entire
payload. The first valid launch envelope is immutable for that process; downloaded
updates still activate on the next launch. Unknown IDs never match another method.
This constant-text contract is not a Portable IR or arbitrary parameter serializer.

The Activity now handles embedded/native window-frame acknowledgement after the
validated embedded bootstrap arrives. The remote path can acknowledge only from
the matched renderer's `dootahFrame`, retaining launched-ID/current-value checks and
Expo's successful-launch delegate. Wrong-ID or malformed remote payloads cannot
declare a remote render healthy through the embedded/native frame observer.

Normal consumer integration is Application/Activity lifecycle setup plus ordinary
`setContent` and normal composable calls. There is no per-screen Dootah annotation,
wrapper, state collection, frame modifier or dispatch expression. The SDK owns its
remote renderer's frame modifier. `dootah.composeDispatch = false` is a build-wide
native control/opt-out, not a per-screen annotation.

## Bytecode, build and shrinker evidence

The plugin's six tests passed with **zero skipped tests**, including four entry/identity
tests against the actual compiled consumer forms. They compare the original instruction
objects, descriptors, access flags, annotations and handlers; verify no argument-local
stores; check distinct overload identities and repeated-transform idempotence; exclude
helpers/non-composables/unknown appliers; and execute fallback, early return and default
mask paths on a JVM. The JVM execution fixture substitutes a recording body while
retaining real member metadata; separate preservation assertions use unmodified bodies.
The runtime envelope regression test also passed. The unchanged Phase 3 native health
patch retains its previously accepted regressions; no unrelated compiler matrix was run.

Normal compilation, debug APK/D8 and release APK/R8 passed. The physical acceptance
APK is minified with Android's standard optimized ProGuard configuration. Its OTA
proof exercises the shrunken entry and renderer. No verifier, descriptor, stack-frame,
RETURN, Compose runtime or native-registration failures occurred in accepted stages.
Configuration cache was stored and reused; a repeated two-variant build completed
with 84 of 85 tasks up to date. An additional conservative applier eligibility guard
was finalized after installation: all nine release class files stayed byte-identical,
and the final rebuilt APK has byte-identical DEX, all 14 native libraries and Hermes
bootstrap to the accepted APK. The rebuild generated a new embedded manifest and
was **not installed**. Acceptance retains the original installed identity and hash.

Reproduce host checks after publishing the SDK with `build-sdk.sh`:

```sh
# Set JDK 21 and ANDROID_HOME first.
v2/android-sdk/tests/compose-hook.sh
```

The script compiles real fixtures, runs the fixture-backed `composeFixtureTest` task and
builds both Android variants; `build-sdk.sh` always runs it. The fixture consumes the SDK
version in `v2/android-sdk/version.properties`, so it cannot silently build against an older
local artifact. Real-form tests never skip: without `-PcomposeFixtures` they fail, and
`tests/require-tests.mjs` fails any required suite that is missing, empty, skipped or
failed. The fixture sets `dootah.requireHooks`, so zero instrumented entry points fail its
build. SDK producer tests remain maintainer work; the consumer needs neither npm nor Kotlin
source analysis.

## Physical proof

Device: Samsung **SM-E426B**, Android 13, arm64-v8a. Accepted package
`dev.dootah.consumer`, versionCode **1**, versionName **0.4.0**, runtime
`dootah-v2-hook-1`, installed **2026-09-27 19:47:26 IST**.

APK SHA-256: `778b3414da61bcabc1f1a3e043c46b70bfb4cd364b06764f303f31ac791c3068`.
Embedded UUID: `421a1346-fe3b-4bbf-a04b-9b9093261d97`.
Checkout function ID:
`dth1:2c10315110b96541844254b7424bd34945adb697b298ea78ce4e0e5c26e4ca85`.

The installed APK was pulled before and after the OTA proof and matched the accepted
build hash and install timestamp. All accepted captures
check native UI, launched update, real frame/Expo renderer acknowledgement where
applicable, absence of fatal exceptions and unchanged install timestamp.

| Stage | Result on the same accepted APK |
| --- | --- |
| Baseline / online restart | Original checkout, receipt, overloads, members, default, extension, generic and stateful UI; empty registry; native frame health |
| Signed OTA download | Native baseline remains active until process restart |
| Automatic activation / online restart | Checkout becomes `Dootah automatic dispatch OTA`; receipt and both overloads stay native; remote frame health |
| Offline cached restart | Package network blocked and ADB reverse removed; failed network check, cached OTA still renders and acknowledges health |
| Multiple targets | Checkout and String overload render their separate remote titles; Int overload, receipt and unsupported List composable remain native |
| Wrong function ID | Valid signed envelope targeting an unknown ID renders all native; no false remote health acknowledgement |
| Invalid remote contract | Signed artifact with ABI 2 rejected as a whole; native UI remains usable; no false remote health acknowledgement |
| Recovery publication | A newer valid checkout update renders and acknowledges health |
| Signed rollback / activation | Expo's unchanged `rollBackToEmbedded` restores original UUID and native implementations |
| Further rollback restart | Native implementations and embedded frame health survive another launch with backend connection unavailable |

The remembered counter also changed from 0 to 1 while another composable was
overridden. Generic, unsupported List, generated bridge and default-argument paths
stay native; an OTA for the String overload never replaces the Int overload.

Publisher artifacts were manually prepared in an internal export workspace using
the existing locked Hermes/Metro tooling, then published by unchanged
`v2/server/publish.mjs`. No JS tree or publishing step was added to the consumer.
Host verification checked exact signatures and asset SHA-256; device logs record
Expo signature verification and download completion. The initial checkout OTA is
UUID `cdc179aa-8d21-c909-d88f-2da687288130`, integer ID `17905187488862`,
1,433,741 bytes, hash `oG1oaG9lQj3G6_V1dtNVEFEA6YmHRIb42EnApwSsA-c`.
The authenticated rollback is ID `17905205594992`, signed commit time
`2026-09-27T14:49:19.508Z`. Existing signing/hash enforcement was preserved; Phase 2C's
cryptographic rejection probes were not repeated. The invalid-artifact test here
tests the new dispatch contract after transport signature validation.

Before acceptance, an initial diagnostic build lacked hooks because of the AGP
annotation-summary filter described above. A diagnostic first launch also exposed
native frame-listener registration timing: registering/removing it at window
attachment/detachment corrected that before the accepted baseline. Neither attempt
counts as an accepted baseline. No main-package APK replacement, data clear, client
database mutation, signing bypass, synthetic React render marker or React surface
occurred after acceptance. Benchmark APKs use a separate `.validation` package.

Curated [acceptance record](evidence/phase4-20260927/acceptance.json) and
[function identities](evidence/phase4-20260927/function-ids.json) accompany screenshots:
[baseline](evidence/phase4-20260927/baseline.png),
[automatic OTA](evidence/phase4-20260927/ota-active.png),
[offline](evidence/phase4-20260927/ota-offline.png),
[overload isolation](evidence/phase4-20260927/overload-active.png),
[wrong ID](evidence/phase4-20260927/wrong-active.png),
[invalid contract](evidence/phase4-20260927/invalid-active.png),
[rollback](evidence/phase4-20260927/rollback-active.png),
[rollback restart](evidence/phase4-20260927/rollback-restart.png).
Raw logs, APKs, publisher exports, build outputs and private credentials remain
outside Git. Working evidence is under `/tmp/dootah-v2-phase4`.
Cleanup restores package networking, disables the temporary deny chain, removes
ADB reverse, restores the five-minute screen timeout and removes the diagnostic
`.validation` package. The accepted main package remains installed. The existing
server container is stopped; PostgreSQL and persistent publication/rollback state
are preserved.

## Supported scope and remaining risks

This is a bounded constant-text replacement proof, not Portable IR, business-logic
execution or arbitrary Kotlin argument serialization. Calls with nonzero default
masks stay native even if an override exists. Unknown metadata/ABI shapes and
non-Android UI appliers stay native. Only application classes are transformed;
dependency composables are excluded. ID stability is bounded by the compiled ABI.

Validated toolchain: Kotlin/Compose compiler 2.1.20, AGP 8.12.0, JDK 21; runtime
Android API 29+ / arm64 from Phase 3. Configuration-cache reuse is demonstrated for
this consumer, not a full Gradle/AGP compatibility matrix. The metadata reader is a
public standalone library, not a Kotlin compiler plugin or compiler-version bridge.
RN/Expo inherited capabilities and the Phase 2C HTTP-cache availability limitation
remain. A wrong-ID or rejected payload stays native and receives no remote health
acknowledgement; this phase does not add a new quarantine/recovery protocol.

## Measurements

The accepted minified release APK is **24,123,635 bytes (23.01 MiB)**. Comparing it
directly with Phase 3's unminified APK would conflate shrinking and sample changes.
To isolate instrumentation, fresh debug packages from the same consumer/runtime
were built with dispatch enabled and disabled: 83,585,039 versus 83,585,043 bytes
(**-4 bytes**, effectively zero at APK ZIP/signing alignment granularity).
The actual DEX growth is **900 uncompressed bytes**. All other archive member bytes
match except the regenerated embedded UUID manifest, which has equal length.
This is a small-fixture measurement, not a general per-application size prediction.

`am start -W` reported **644 ms** for the accepted first cold Activity launch versus
**424 ms** for Phase 3's recorded first launch. Phase 4's later accepted cold-process
launches ranged **236–361 ms**. These are Activity launch times, not time to Hermes
readiness, and the sample screens/shrinking differ; the 220 ms first-launch difference
cannot be attributed to the entry hook. No controlled Phase 3 startup regression
claim is made.

The debug-only `BenchmarkActivity` compares repeated ordinary empty UI-composable
calls against a dispatch-disabled control on the same phone. It uses three process
runs per variant, seven rounds of 10,000 calls, and separately measures 100,000
empty-registry lookups per round. The explicit standard `ComposableTarget` on the
empty probe supplies the Android UI applier that an empty body cannot infer. It is
not a Dootah annotation and is absent from the production screen integration.
Results include normal Compose work, ART warmup and debug overhead; this is a rough
diagnostic, not an AndroidX Benchmark or production percentile result.

Median final-round cost across the three runs was **9.71 µs/call with the hook**
versus **6.54 µs/call without**, an approximate **3.17 µs added per native-only call**.
Early rounds were slower and still warming. The separate empty-registry map lookup
median after two warmup rounds was **10.48 ns**; this excludes composition-local
access and snapshot observation and is not a populated-table hit benchmark.
Full numerical rounds are preserved in the acceptance record. No size or startup
optimization is claimed, and larger-screen release profiling remains future work.

## Exact Phase 5 starting point

Implement a separate read-only publishing analyzer that reuses
the installed function identity/contract and emits the bounded Portable IR/JS.
Keep analysis/transpilation out of normal Android compilation. Phase 4 stops here;
no Phase 5 source analysis, transpilation or Portable IR implementation is included.


## Phase 6 typed entry ABI 2

The new installed prefix calls `ComposeEntry.tryRenderV2(id, types, values, composer, 0)`.
Only supported String/Boolean/Int parameters and explicit nullable variants are
boxed into its internal array. The runtime validates these types before JSON
serialization; Composer, receivers/JVM objects and scopes never cross to the engine.
Unsupported signatures remain native. Nonzero default masks execute the original
body. Existing ABI 1 is not reinterpreted as typed ABI 2.

The prefix still preserves the original method and metadata. An unresolved portable
result performs a ReadOnlyComposable snapshot lookup and queues bounded worker
execution; it creates no Compose groups before entering the original native body.
Only a ready result calls the separate render composable. A compiled-bytecode test
protects this rule, and physical recomposition checks cover native default-member
rows and a stateful counter. The initial Phase 6 candidate violated this invariant;
its failure, signed restoration and corrected acceptance are preserved in
[SECURITY_MODEL.md](SECURITY_MODEL.md).


## Phase 8 metadata reader update

Kotlin 2.3.20 and 2.4.20 write metadata version 2.4.0, which `kotlin-metadata-jvm` 2.1.21
could not read, so every class silently stayed native. The plugin now uses
`kotlin-metadata-jvm` **2.4.20** (strict reading up to 2.5.0) and logs one warning when
metadata is unreadable. Selection rules, the prefix and the `FunctionIdentity` algorithm
are unchanged; a regression rewrites the real fixture to metadata 2.3–2.5 and asserts
identical IDs, while 2.6 stays native. Real-app bytecode verification (Mihon, JetNews,
Read You, NiA) found 0 Dootah-attributable changes outside hooked prefixes and coexistence
with Hilt and Firebase Performance transforms. See [REAL_APP_ACCEPTANCE.md](REAL_APP_ACCEPTANCE.md).
