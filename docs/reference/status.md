# Alpha status and evidence

As of 2026-10-18, the public SDK is **0.1.0-alpha.2**, an experimental alpha:
development SDK **0.7.5-local**, Runtime ABI **3**, Logic ABI **1**. Dootah-owned code is
Apache-2.0. The SDK and plugin are served by JitPack from the release's validated stage.
Maven Central and JFrog publication are not claimed. An experimental hosted Cloud runs at
https://dootah.pages.dev.

## Alpha.2 (Runtime ABI 3) evidence

Each capability passed same-APK OTA on a physical device in a separate Mihon test
install (R8 release build, installed once, ordinary source edit published through
Cloud, no rebuild or reinstall). Raw acceptance logs contain operator state and stay
private, outside the repository.

| Capability | Device | What was shown | Limit |
| --- | --- | --- | --- |
| Structural Compose UI | Vivo 1909, Android 12 | Box/Text/`if` changes, signed OTA, native rollback, per-app offline persistence | One app and device |
| Build-generated native adapters | Vivo 1909, Android 12 | Changed condition around an installed app component; same native pixels | No raw evidence in the repository |
| Nested components | Samsung SM-E426B, Android 13 | Installed children reordered inside an installed `Column`; same native pixels | No raw evidence in the repository |
| Compose state and installed callbacks | Vivo 1909, Android 12 | `mutableStateOf(false)` → `true` in a multi-slot `AlertDialog`; toggling, Remove/Cancel/dismiss reached the installed callbacks with the current state | Exactly-once execution is proven by JVM tests; the app's handlers are idempotent |
| Scalar business logic | Both devices | Logic ABI 1 conditions and values in every edit above, and earlier ABI 2 trials | — |

Local module suites passed on the alpha.2 source. Rollback, offline and signing
lifecycle checks were not repeated for the adapter, nested and interaction slices;
they reuse the structural and alpha.1 evidence because delivery is unchanged.

**GitHub-hosted V2 Actions passed on the alpha.2 release commit `51b991c`** in this
repository: push run 38147839773 (2026-10-11) and scheduled run 38231238658
(2026-10-12). The SDK, server and security jobs and the **V2 required suites**
aggregate succeeded in both. They prove only that commit.

## Earlier validation

The earlier ABI 2 acceptance covered signed delivery, offline restart and native
rollback in Mihon, Read You and JetNews on one Samsung Android 13 device. Now in
Android was only partially accepted; Tivi's toolchain was not integrable in that
trial. These results do not establish universal Kotlin or device compatibility.

Earlier local SDK/server/security checks included isolated consumer builds and a
fresh-volume restore. Those dated results do not certify later source revisions.
The current [CI guide](../../v2/ci/README.md) specifies reproducible acceptance gates;
the [quickstart](../getting-started/quickstart.md) specifies same-APK OTA verification.
Historical observation fixtures under SDK security tests check recorded data only,
not fresh execution. Internal investigations, raw logs and operator state remain private.

See [platform limits](platform.md) and [supported changes](support.md) before a trial.
No public artifact publication or production hosting is implied by these checks.
