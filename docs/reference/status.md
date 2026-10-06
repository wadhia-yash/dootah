# Alpha status and evidence

As of 2026-10-05, the public SDK candidate is **0.1.0-alpha.1**, development SDK
**0.7.5-local**, Runtime ABI **2**, Logic ABI **1**, BasicText / Option A.
Dootah-owned code is Apache-2.0. Public Maven/JFrog publication is not claimed.

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
purpose; later records may resolve earlier findings. No new device proof, artifact
publication, deployment or public GitHub release is claimed by the documentation work.
