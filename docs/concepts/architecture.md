# Native Android with bounded remote logic

Dootah is an OTA layer for an existing Kotlin/Compose application. Its installed APK
owns native rendering, device access, updater configuration and the trust anchor.

1. **Native build:** the `dev.dootah` Android Gradle plugin adds dispatch prefixes to
   eligible Compose methods in the application module, after Kotlin compilation.
   Original method bodies remain available. A retained release records the exact APK,
   classes, source snapshot, hooks and installed capabilities.
2. **Publish:** the separate read-only Kotlin PSI analyzer compares source with that
   retained baseline. Only supported body edits become Portable IR. It does not mutate
   Kotlin FIR/IR, run as a compiler plugin or invent hooks missing from the APK.
3. **Deliver:** Cloud authenticates publication and validates the installed contract.
   Its worker uses a private xprem binding for signed manifests and content-addressed
   assets. Native Expo Updates verifies/selects/caches the update.
4. **Execute:** the launch asset is IR JSON, not an executable React entrypoint.
   The installed `PortableProgram` validates it and regenerates JavaScript. A fresh
   AndroidX JavaScriptSandbox isolate receives scalar values and returns a string encoding validated scalar results. Structural trees stay in the signed artifact and are validated natively.
5. **Render:** `ComposeDispatch` validates the result and uses the installed native
   `compose.basicText.v1`, `compose.layout.v1`, or `compose.material3.text.v1` capabilities. Until a valid result exists, the original body
   executes. `logic.pure.v1` supplies bounded deterministic calculations, not device APIs.

RN/Hermes/Expo dependencies remain packaged and contribute significant APK weight.
The current Application does not create a ReactHost or expose native registries to
remote logic. The consumer has no React screen tree, Metro step or npm project.

A compatible update requires agreement between source identity, retained contract,
installed hook, runtime version, Runtime ABI **3**, Logic ABI **1**, capability names
and portable input types. The configured `runtimeVersion` is an application-chosen
compatibility string, distinct from SDK and ABI versions; do not change it for each
OTA. A new incompatible native baseline needs an appropriate new runtime/environment.

See [supported source](../reference/support.md), [delivery](delivery.md) and
[security](../security/README.md). Detailed implementation and dated experiments
remain in [engineering history](../history/README.md).

Alpha.2 uses Runtime ABI 3, which requires a new baseline APK; alpha.1 and its
acceptance records describe ABI 2. ABI 3 adds bounded structural UI, build-generated
native adapters with nested content, and Compose state with installed callbacks
(negotiated as `compose.interaction.v1`). Each passed physical-device acceptance
(see [status](../reference/status.md) and the
[structural record](../v2/MINIMAL_STRUCTURAL_OTA.md)). The `tryRenderV2` hook
signature remains unchanged.
