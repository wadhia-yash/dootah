# Compose instrumentation

The Android Gradle plugin instruments application-module Compose methods after Kotlin
compilation. Original native bodies remain available for fallback. It does not require
per-screen annotations or change the Kotlin compiler. Library-module bodies are not
instrumented. See [supported source forms](support.md) before selecting an OTA target.

## Hook selection and compatibility

A generated hook alone does not guarantee that an edit can be published. The publisher
compares ordinary Kotlin source against the exact retained native release and refuses
unsupported changes. Function identity and portable parameter signatures must agree
between the installed hook, retained source/classes, registered contract and artifact.
Changing signatures, imports or installed native capabilities requires a new baseline.

The installed `tryRenderV2` entry receives supported scalar parameters and tries a
validated override. Missing or incompatible artifacts, unavailable sandbox execution,
and invalid results retain the original implementation. Structural rendering and
installed callbacks remain native; see [architecture](../concepts/architecture.md)
and [security](../security/README.md).

## Diagnosing zero hooks

The plugin reports the instrumented count for each variant. Zero hooks means every
composable stays native and `dootah import` refuses that build. Check that the target
is in the application module, uses a supported parameter form, and has recognizable
Kotlin metadata. Inspect the generated hook report and retained release for that exact
variant. Do not bypass the refusal or invent function IDs.

For source-specific selection and lowering limits, use the [compatibility matrix](support.md).
For build, retention, import and analysis commands, follow the
[quickstart](../getting-started/quickstart.md) and
[CLI reference](../../v2/publishing/ONBOARDING.md).

## Maintainer verification

`v2/android-sdk/tests/compose-hook.sh` exercises real Compose builds and shrinker
behavior. Hook metadata and bytecode tests live under the Gradle plugin tests.
The contributor guide lists module checks. These checks do not prove device OTA:
install the baseline once, publish an ordinary supported edit without reinstalling,
verify native rendering, then check rollback and offline behavior.
