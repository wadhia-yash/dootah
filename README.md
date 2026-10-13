# Dootah

Dootah lets native Android/Jetpack Compose applications deliver bounded UI and
business-logic updates over the air while retaining native fallback.

Keep writing Kotlin/Compose. Publish compatible changes to logic, text, bounded
Compose structure, installed app components, Compose state and callback wiring without
rebuilding an installed APK, provided its native capabilities already support them.
New native libraries, permissions, manifest changes and capabilities require a new APK.

**Experimental source-only alpha.** Public SDK **0.1.0-alpha.2**; development SDK
**0.7.5-local**; Runtime ABI **3**; Logic ABI **1**. There are no registry artifacts:
build a local Maven stage from this checkout (see the quickstart). Maven Central/JFrog
artifacts are not announced as available. Runtime ABI 3 is incompatible with
alpha.1's ABI 2: apps need a new baseline APK and a new runtime version/environment.
GitHub-hosted CI passed on the release commit `51b991c`. See
[validation status](docs/reference/status.md).

## Start here

- [Website and interactive demo console](https://wadhia-yash.github.io/dootah/)
- [Quickstart: integrate, publish, verify and roll back](docs/getting-started/quickstart.md)
- [Minimal Kotlin example](v2/examples/basic-text/README.md)
- [Supported source and unsupported changes](docs/reference/support.md)
- [Android requirements and alpha limits](docs/reference/platform.md)
- [Security model](docs/security/README.md)
- [Self-host Cloud](docs/self-hosting/README.md)
- [Report a bug or share OTA trial feedback](https://github.com/wadhia-yash/dootah/issues/new/choose)
- [Documentation index](docs/README.md) · [Contributing](CONTRIBUTING.md)

## How it stays native

```text
Kotlin/Compose source -> read-only analyzer -> Dootah Portable IR
    -> signed OTA delivery -> native validation -> AndroidX JavaScriptSandbox
    -> typed native capabilities -> real native Compose / native fallback
```

The Android Gradle plugin instruments eligible application-module Compose entrypoints
after Kotlin compilation. No Dootah Kotlin compiler plugin or per-screen annotation
is required. The installed app keeps each original body. Remote programs receive
bounded scalar inputs; installed native code renders their validated result.

Dootah internally packages Expo Updates and dependencies including React Native and
Hermes. Consumers do not migrate to React Native, write JavaScript screens or add
an npm project. Current remote logic runs in the separate AndroidX JavaScriptSandbox;
it is not loaded into a React host. Native Expo machinery handles signed delivery,
caching and recovery. See [architecture](docs/concepts/architecture.md).

## Integrate and deliver the first update

Use **minSdk 29+, arm64-v8a, compileSdk 36+, AGP 8.9.1+ and JDK 21** with a compatible
Android toolchain. The tested consumer uses Gradle 9.3.1, AGP 8.12.0 and Kotlin 2.1.20.
A suitable WebView sandbox provider is also required for remote execution.

Until publication, build a local public-version Maven stage from this checkout.
Apply `dev.dootah`, subclass `DootahApplication`, and configure `dootah {}` with your
Cloud app, HTTPS endpoint, channel, runtime version and public update certificate.
Your Activity can remain `ComponentActivity`.

Build, retain and install the exact native APK. Import its retained record, register
its contract with Cloud, edit a supported Kotlin body, run `dootah analyze` and
`dootah publish`, then enable a rollout after the operation succeeds. Verify the
change on the same installed APK without rebuilding/reinstalling it. The
[quickstart](docs/getting-started/quickstart.md) supplies the actual CLI commands.

`dootah rollback` requests a signed return to the embedded/native baseline. Inspect
the asynchronous operation, let the device check for it, and restart to verify.
Pausing stops new offers; it does not remove a cached update. Offline devices cannot
receive a new rollback immediately.

## Hosting and trust

Operators run Cloud behind HTTPS/Caddy, with xprem and PostgreSQL on private Docker
networks. Cloud manages authorization, contracts, release operations and rollout;
xprem handles private signed delivery/storage. The alpha is a single-host,
operator-managed system with explicit migrations and backup/restore procedures.
There is no managed Dootah service promised here. Start with
[self-hosting](docs/self-hosting/README.md).

Signatures and asset hashes authenticate updates. Installed Portable IR validation,
typed capabilities, bounded work and isolated execution constrain what remote logic
can do. Invalid or unavailable execution falls back to the original native body.
Signatures cannot prevent business mistakes; OS/WebView and operator trust still
matter. Read the [security model](docs/security/README.md) before adoption.

## Current limits

Updates can change bounded String/Boolean/Int logic and local pure helpers; a
Box/Material3 Text structure with `if` / `if-else`; calls to app-owned components
that the installed build already makes (through build-generated native adapters,
including nested content slots); and Compose `remember` state with the wiring of
installed callbacks. Everything else stays native: object models and data classes
as inputs, collections, loops, arbitrary modifiers/layouts, library-module
composables, navigation and general Kotlin execution. Unsupported edits refuse
publication; unsupported native code continues to run normally. See the
[support matrix](docs/reference/support.md).

Measured release APK growth was about **22–31 MB**. Native OTA evidence covers two
physical phones (Samsung Android 13, Vivo Android 12) in open-source test apps; this
is not broad device qualification. Enrollment needs
an authenticated customer backend and renewal integration. Telemetry health means
render acknowledgement, not crash-free analytics. Hosting has no HA or PITR.
See the [complete limitations](docs/reference/platform.md).

## Project and license

Dootah-owned code is [Apache-2.0](LICENSE). [NOTICE](NOTICE) and the generated SDK
license inventory describe third-party components; xprem has a separately audited
[pinned community boundary](v2/server/XPREM-LICENSE.md). Alpha compatibility and
operational maturity are limited to the documented evidence.

V2 development lives under `v2/`. The legacy root Gradle build and V1 material are
historical, retained pending a separate cleanup; they are not the V2 build entrypoint.
V1 is preserved on `archive/dootah-v1-pre-v2`. See [repository history](docs/history/README.md).
