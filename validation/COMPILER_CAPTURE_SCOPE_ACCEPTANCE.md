# Compiler compatibility and native capture scope — 2026-09-20

The reported Seal compiler failure is reduced, fixed generically, and verified on
both compiler lines and both real consumers. Seal's entire
`:app:compileGenericDebugKotlin` task succeeds on its original Kotlin **2.0.20**;
JetNews's `:app:compileDebugKotlin` succeeds on Kotlin **2.3.20**. No further compiler
defect appeared in those builds. This is compiler acceptance, not OTA proof.

## Review of the incoming work

The working tree contained the multi-Kotlin refactor and Opus's continuation.
Those changes were reviewed and preserved:

- `dootah-compiler-core` owns compiler-independent source descriptions, models,
  semantic lowering, freeze/propagation and generated bundle behavior.
- Shared FIR/IR integration is compiled with thin `backend-2.0` / `backend-2.3`
  ABI adapters. There is no second implementation of the semantic lowering.
- `gradle/compiler-backends.properties` drives artifact production, automatic
  Gradle selection and the compiler test matrix. Support is verified for exact
  versions within each family; unverified patches are not assumed compatible.
- `BackendAbi` correctly anchors metadata lookup on a class in Dootah's jar.
  `ExtensionStorage`'s implicit receiver is not a valid plugin classloader anchor.
- Kotlin 2.0 uses its legacy `ComponentRegistrar` ordering mechanism. Both real
  consumers still report `BEFORE_COMPOSE` after the fix.
- Suspend callbacks remain native/frozen. Their bodies are not copied into
  non-suspend callback adapters.

The new production change for this blocker is confined to the shared
`NativePrologue.declaredScope()` implementation. There are no application names,
application-specific mappings or new ABI exceptions in that change.

## Reduction and proof

A fresh baseline Seal compilation reproduced:

```text
DownloadSettingsDialog.kt
No mapping for symbol: VALUE_PARAMETER name:type index:0 type:...DownloadType
```

The failing generated adapter's closure constructor read `type` at the outer
`DownloadSettingDialog` interception point, but its declaration belonged to a
lambda nested inside a stored composable lambda's initializer. That lambda was
not an ancestor of the generated adapter, nor part of its copied region.

The first test in `NativeCaptureScopeTest` reduces this ownership relationship
without Seal dependencies:

```kotlin
@Composable fun Entries(content: @Composable (Int) -> Unit) { content(1) }
@Composable fun Choice(onClick: () -> Unit) {}
fun consume(value: Int) {}

@Composable fun Screen() {
    val content: @Composable () -> Unit = {
        Entries { type -> Choice(onClick = { consume(type) }) }
    }
    Text("remote sibling")
    content()
}
```

Before the fix this fails on **both** compiler versions with `No mapping for
symbol: VALUE_PARAMETER ... name:type ... type:kotlin.Int`. After the fix it
compiles on both versions, retains the complete `Entries` native region, and
does not advertise an independently lifted `Choice` region with an invalid
capture. The test asserts the contract as well as successful code generation.

The actual invariant is lexical visibility of external captures:

1. A copied region's own declarations must be mapped with that region.
2. References outside the region must resolve to declarations visible at its
   destination, or to explicitly rebound parameters.

The second rule was violated. `declaredScope()` recursively collected **every**
declaration under each hoisted prologue variable, including declarations inside
its initializer. Subtracting that collection from the forbidden body scope
incorrectly permitted captures of nested parameters, receivers, locals and local
functions. Both the selection check and the final scope check used this incorrect
scope.

The correction records only declarations made visible at the interception point:
the hoisted value itself, or a delegated property's storage and accessors. An
initializer's nested declaration scopes remain unavailable. Existing capture
checks consequently reject incomplete regions while allowing complete regions
that own their declarations. Top-level delegated-state accessors remain usable.

This is not a declaration-allocation ordering defect in `deepCopyWithSymbols`:
the Kotlin 2.0.20 and 2.3.20 implementations already traverse the complete supplied
subtree with `DeepCopySymbolRemapper` before transforming it. A second allocation
pass cannot give an out-of-region parameter a meaningful value at the new site.
No custom remapper or arbitrary IR transplantation rewrite was necessary.

## Regression coverage and results

Five compact scope fixtures cover nested content/handler lambdas and captured
parameters; nested delegated state, destructuring and local functions; extension
receivers; local classes with dispatch receivers, generic methods and default
arguments; and conditionally created callbacks. Existing suspend-handler and
IdeaMemo-derived delegated-local regressions pass on both lines.

| Check | Result |
| --- | --- |
| Kotlin 2.0.20 compiler tests | 156 passed |
| Kotlin 2.3.20 compiler tests | 156 passed |
| Compiler-independent core | 1 passed |
| Gradle plugin | 34 passed |
| Contract | 67 passed |
| Android runtime | 194 passed |
| Server | 33 passed |
| `dootahRealComposeCheck` | Passed |
| JetNews, Kotlin 2.3.20 | Compilation passed; before Compose; 59/59 intercepted |
| Seal, Kotlin 2.0.20 | Compilation passed; before Compose; 196/196 intercepted |

The 641 tests have zero failures, errors or skips. The Gradle tests include real
isolated consumers for automatic backend selection on both supported versions,
both Kotlin/Dootah declaration orders, and a controlled refusal of real Kotlin
2.2.20. Backend metadata/classloader tests pass on both lines. Unsupported version
and Compose-ordering guards remain enabled.

The broader check used JDK 17:

```sh
./gradlew dootahCompilerMatrixCheck :dootah-contract:test \
  :dootah-android:testDebugUnitTest :dootah-server:test \
  --max-workers=2 --continue --console=plain
```

Consumer validation used JDK 21, the installed Android SDK, and the new local-only
coordinate `0.1.0-ir-scope-validation.1`. All SDK artifacts were staged together
using `publishDootahToMavenLocal -PdootahVersion=0.1.0-ir-scope-validation.1`.
A temporary init script selected that Gradle plugin without editing either app:

```groovy
settingsEvaluated { settings ->
    settings.pluginManagement.resolutionStrategy.eachPlugin {
        if (requested.id.id == 'dev.dootah') {
            useModule('dev.dootah:dootah-gradle-plugin:0.1.0-ir-scope-validation.1')
        }
    }
}
```

```sh
# In the existing JetNews checkout:
./gradlew :app:compileDebugKotlin -I /tmp/dootah-ir-consumer.init.gradle \
  --no-configuration-cache --console=plain
# In the existing Seal checkout:
./gradlew :app:compileGenericDebugKotlin -I /tmp/dootah-ir-consumer.init.gradle \
  --no-configuration-cache --console=plain
```

Dependency insight confirms automatic resolution of `dootah-compiler-plugin` for
JetNews and `dootah-compiler-plugin-kotlin-2.0` for Seal, both at that exact local
validation version. The init script selects only the Dootah release, never an ABI
adapter. Both app compilation tasks executed; neither result was an up-to-date
skip.

No acceptance application files were edited during this investigation. These were
the existing acceptance checkouts, not pristine clones: Seal already had Dootah
repositories/plugin integration, compileSdk 36, minSdk 26 and build-memory
settings; JetNews already had its prior integration/OTA fixture changes. Seal's
`app/src`, `color/src` and Kotlin version catalog have no diff against its checkout
HEAD. Its Kotlin version was not upgraded.

## Boundaries and release state

There is no evidence from these runs of a second unrelated transplantation defect
or a need to rewrite the architecture. This establishes the corrected capture
invariant and these consumer compilations, not universal compatibility with every
Kotlin construct. Actual IdeaMemo/device OTA behavior was not rerun; its focused
compiler regressions and the runtime suite pass.

RuntimeVersion remains **9**. No APK was installed, no Seal OTA test was resumed,
and no immutable alpha was published. No release signing key is configured through
`DOOTAH_MAVEN_SIGNING_KEY_FILE`. Alpha.17 remains absent from the alpha repository;
SHA-256 comparison confirms all **1,591** pre-existing versioned alpha files are
unchanged. The local validation coordinate is not a release.

Local diagnostic logs (ephemeral, outside the repository):

- `/tmp/dootah-seal-baseline.log`
- `/tmp/dootah-compiler-plugin-capture-red.txt`
- `/tmp/dootah-compiler-plugin-kotlin-2.0-capture-red.txt`
- `/tmp/dootah-scope-all-tests.log`
- `/tmp/dootah-scope-candidate.log`
- `/tmp/dootah-scope-jetnews.log`, `/tmp/dootah-scope-jetnews-backend.log`
- `/tmp/dootah-scope-seal.log`, `/tmp/dootah-scope-seal-backend.log`

## Checkpoint review

The commit preparation reruns the matrix and broader test command above. A staged
source-only checkout also exposed a Gradle 9 configuration prerequisite: generated
backend project directories must exist, although their contents are build outputs
and are deliberately not tracked. Settings now create those directories from the
same compatibility matrix. The isolated checkout's offline `help` task passes with
this correction. No compiler behavior or runtime version changes in this cleanup.
