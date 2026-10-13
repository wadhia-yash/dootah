# Alpha status and evidence

As of 2026-10-11, the public SDK is **0.1.0-alpha.2**, an experimental source-only
alpha: development SDK **0.7.5-local**, Runtime ABI **3**, Logic ABI **1**. Dootah-owned
code is Apache-2.0. Public Maven/JFrog publication is not claimed; consumers build a
local Maven stage from the tagged source.

## Alpha.2 (Runtime ABI 3) evidence

Each capability passed same-APK OTA on a physical device in a separate Mihon test
install (R8 release build, installed once, ordinary source edit published through
Cloud, no rebuild or reinstall). Raw acceptance logs contain operator state and stay
private, outside the repository.

| Capability | Device | What was shown | Limit |
| --- | --- | --- | --- |
| Structural Compose UI | Vivo 1909, Android 12 | Box/Text/`if` changes, signed OTA, native rollback, per-app offline persistence ([record](../v2/MINIMAL_STRUCTURAL_OTA.md)) | One app and device |
| Build-generated native adapters | Vivo 1909, Android 12 | Changed condition around an installed app component; same native pixels | No raw evidence in the repository |
| Nested components | Samsung SM-E426B, Android 13 | Installed children reordered inside an installed `Column`; same native pixels | No raw evidence in the repository |
| Compose state and installed callbacks | Vivo 1909, Android 12 | `mutableStateOf(false)` → `true` in a multi-slot `AlertDialog`; toggling, Remove/Cancel/dismiss reached the installed callbacks with the current state | Exactly-once execution is proven by JVM tests; the app's handlers are idempotent |
| Scalar business logic | Both devices | Logic ABI 1 conditions and values in every edit above, and the alpha.1 suite below | — |

Local module suites passed on the alpha.2 source. Rollback, offline and signing
lifecycle checks were not repeated for the adapter, nested and interaction slices;
they reuse the structural and alpha.1 evidence because delivery is unchanged.

**GitHub-hosted V2 Actions passed on the alpha.2 release commit `51b991c`** in this
repository: push run 38147839773 (2026-10-11) and scheduled run 38231238658
(2026-10-12). The SDK, server and security jobs and the **V2 required suites**
aggregate succeeded in both. They prove only that commit.

## Alpha.1 (Runtime ABI 2) evidence

These records describe the earlier ABI 2 release and remain valid for its delivery,
hosting and security machinery.

| Evidence | What it establishes | Limit |
| --- | --- | --- |
| [Required-suite receipt](../v2/evidence/phase9e-20261004/required-suites-result.json) | 450 executed passing tests, zero failures, four named xprem Azure exclusions, zero unexpected skips | Local run, not hosted Actions |
| [CI acceptance record](../v2/PHASE9E_CI.md) | Fresh SDK stage and consumer, production Compose and fresh-volume restore, security/signing regression | macOS/ARM64 host; server AMD64 emulation; no production signing identity |
| [Real app acceptance](../v2/REAL_APP_ACCEPTANCE.md) | Same-APK native OTA, offline behavior and rollback in accepted open-source apps | One Samsung Android 13 device; limitations and unsuccessful apps recorded |
| [Documentation acceptance](../v2/PHASE9F_DOCS.md) | Public guides, 48 focused test cases, exact Android snippets and retained-release import/analysis | No new device proof or full 450-test rerun |
| [Public artifact acceptance](../v2/PHASE9B_RELEASE.md) | Packaging, license inventories, local staging and isolated consumer | No public repository publication |
| [Customer tooling acceptance](../v2/PHASE9C_ONBOARDING.md) | Standalone CLI and contract/enrollment workflow against disposable services | Not a new physical-device OTA proof |
| [Deployment acceptance](../v2/PHASE9D_DEPLOYMENT.md) | Production-mode local stack and restore | No external deployment |

**GitHub-hosted V2 Actions passed on commit `e329f83`** (run 37286071657, 2026-10-05):

- The SDK, server and security jobs and the **V2 required suites** aggregate succeeded.
- 17 required suites ran: 468 passed, 0 failed, and only the four named xprem Azure
  exclusions.
- Every job receipt is bound to that source commit. The server job ran the production
  Compose stack and a fresh-volume restore on Linux AMD64.

The run is in a private CI repository and its artifacts are not published here. It
proves only `e329f83`. Any later commit, including the eventual release commit, needs
its own hosted run. No CI badge is added. The [CI guide](../../v2/ci/README.md#user-action-required--github-verification-and-later-signing)
lists the remaining verification and signing steps.

The [platform limits](platform.md) and [support matrix](support.md) are current
product references. Dated evidence retains historical SDK versions and paths on
purpose; later records may resolve earlier findings. No artifact publication or
deployment is claimed.
