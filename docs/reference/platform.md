# Alpha platform requirements and limits

Public SDK **0.1.0-alpha.1**, development SDK **0.7.5-local**, Runtime ABI **2**,
Logic ABI **1**, renderer **BasicText / Option A**. Artifacts are currently source-staged;
public Maven availability is not claimed.

| Area | Current requirement or limitation |
| --- | --- |
| Android | minSdk **29+**, **arm64-v8a only**; no iOS SDK |
| Android build | compileSdk **36+**, AGP **8.9.1+** due to the pinned dependency graph; these floors do not certify every toolchain combination |
| Known consumer combination | JDK **21**, Gradle **9.3.1**, AGP **8.12.0**, Kotlin/Compose plugin **2.1.20**, Java/Kotlin target **17** |
| Other toolchains | Real-app evidence includes AGP 8.13–9.4.1 / Kotlin 2.2.0–2.4.20; unknown metadata stays native with a warning. No arbitrary future-version promise |
| Producer tools | Node **24** (CI **24.20.0**), JDK **21**, Python **3.9+**, Android SDK 36, build-tools 36.0.0 and 35.0.0 (AGP default for the staged consumer), NDK **27.1.12297006**, CMake **3.22.1**; wrapper downloads Gradle 9.3.1 |
| Consumer tools | Android/Gradle/JDK only after artifacts are staged; no consumer npm, Node, Metro or JS app required |
| Publisher CLI | JDK **21** and Node **24** at execution time; build its standalone distribution from source once |
| Sandbox | AndroidX JavaScriptEngine **1.0.0**, a working WebView-provided JavaScriptSandbox with termination and heap-limit features; unavailable features retain native behavior |
| Interception | Eligible Compose entrypoints in the **application module only**; library modules are not intercepted |
| Retention | One APK output per variant; module-contained source roots; keep exact APK, pre-R8 classes, source snapshot and hashes privately |
| Renderer | One unthemed foundation `BasicText` result; no Material typography, arbitrary style/modifiers/layout/callbacks; dark-theme contrast can be poor |
| Footprint | Measured incremental release APK growth **22–31 MB** across accepted apps, largely RN/Hermes/Expo natives; no zero-overhead or controlled startup-regression claim |
| Device evidence | One physical **Samsung SM-E426B, Android 13**; no API 29–32 or second-vendor qualification. Some apps passed R8; JetNews OTA evidence uses debug because its native release crashed |
| Enrollment | Authenticated customer backend supplies ten-minute one-use tickets; native SDK stores a 30-day installation credential; integrator handles renewal |
| Telemetry | Best-effort check/download/activation/render-health/rollback events, not crash-free analytics; no managed alerting |
| Hosting | Operator-managed single-host Compose, explicit migrations, encrypted off-host backups; no HA, clustering, PITR or zero-downtime upgrade claim |
| Hosting evidence | Local Docker Desktop ARM64 and emulated AMD64 acceptance; no external production deployment or native Ubuntu qualification |
| CI | GitHub-hosted V2 required gates passed on commit `e329f83` (Linux AMD64); each later commit needs its own hosted run |

[Support matrix](support.md) gives precise Kotlin limits. [Status](status.md) separates
local, physical-device and hosted evidence. Producer pins are in the
[CI guide](../../v2/ci/README.md#isolation-and-local-execution),
[consumer configuration](../../v2/native-consumer/build.gradle),
[runtime dependencies](../../v2/android-sdk/runtime/build.gradle) and
[release metadata](../../v2/android-sdk/version.properties).
