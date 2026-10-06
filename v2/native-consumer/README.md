# V2 native instrumentation fixture

This app exercises supported and refused Compose shapes for SDK maintainers. Its
fixed app identity, local debug backend and optional compatibility Activity base are
fixture details, not customer integration requirements. Use the
[quickstart](../../docs/getting-started/quickstart.md) and
[minimal source example](../examples/basic-text/README.md) for a new application.

With JDK 21 and Android SDK 36 (`JAVA_HOME`, `ANDROID_HOME`), run
`v2/android-sdk/build-sdk.sh` from the repository root. It builds the matching
**0.7.5-local** development artifacts and runs this fixture's Debug/D8 and Release/R8
gates. The fixture resolves its own checkout's local Maven output deliberately.
It is not a public repository setup. Missing local public test certificate/backend
prerequisites must be supplied by the maintainer test flow; never use its identity
or development signing for a customer release.

For the public **0.1.0-alpha.1** candidate, the
[isolated consumer check](../android-sdk/release/README.md) creates a temporary copy,
generates a disposable public certificate, resolves only the supplied stage and
checks both variants. The fixture's runtimeVersion is `dootah-v2-logic-2`; it is an
app compatibility string, separate from Runtime ABI **2** / Logic ABI **1**.
