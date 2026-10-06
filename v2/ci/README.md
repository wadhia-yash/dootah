# V2 alpha CI and release candidates

**V2 alpha CI / V2 required suites** is the alpha gate. The V1 compiler-compatibility
workflow has been removed; see [history](../../docs/history/README.md). No workflow
uploads to a registry, deploys, creates a GitHub Release or creates/pushes tags.

## Required jobs

`v2-ci.yml` runs on every PR, pushes to `main` and `pivot/dootah-v2`, manual
dispatch, and a weekly schedule. There are no path filters. The reusable
`v2-gates.yml` runs three jobs on Ubuntu 24.04 AMD64:

| Job | Required work |
| --- | --- |
| sdk | Clean `build-sdk.sh`; runtime/native health/plugin tests; real Compose fixture tests and Debug/D8 + Release/R8 hooks; publisher JVM/Portable IR/Logic/CLI tests; packaging regressions; new Maven stage, license/content/path audit, isolated consumer |
| server | Production configuration regressions; pinned xprem source preparation, graph audit and upstream tests; AMD64 Cloud/xprem image builds; Caddy validation; complete disposable Compose integration and fresh-volume backup/restore |
| security | CI negative regressions and real ephemeral-key signing/tamper test; workflow actionlint; all tracked-file Gitleaks scan; reviewed dependency/version pins |

The aggregate job runs even if a matrix job fails or skips. It requires all three
successful jobs and every suite declared by `required-suites.json`, at the same
source commit. Missing files, empty reports/classes, incomplete TAP/Go streams,
failures, cancellations and unexpected skips fail closed. Minimum test counts
are floors, not permission to omit named classes or Cloud tests. When intentionally
changing tests, review the manifest with the change. The only allowed skips are
the four named Azure storage tests; their exact set is required, and they are
reported separately from passes. xprem must verify exactly 341 unchanged upstream
files and zero EE dependencies; its graph/source inventory must be nonempty.

**Restore runs on every PR and every release candidate.** There is no reduced
PR acceptance or scheduled-only escape. Timeouts are 120 minutes per matrix job;
adjust runner resources if actual hosted measurements require it, without silently
dropping gates. The SDK job peaks at roughly 10 GiB of free storage during
production (before its pre-consumer reclaim) and needs 7 GiB free for the
consumer; allow 16 GiB for the server job, 4 CPU cores and 8 GiB RAM as planning
estimates. Hosted run 37270485803 ran out of disk (94 KiB free, 7.2 GiB memory
available) in the consumer before this reclaim existed.

## Isolation and local execution

Install JDK 21, Node 24.20.0, Python 3.9+, Git, GnuPG, OpenSSL, Docker/Compose and
Android SDK 36/build-tools 36.0.0 and 35.0.0 (AGP's consumer default)/NDK
27.1.12297006/CMake 3.22.1. Set `JAVA_HOME`
and `ANDROID_HOME`. Both Gradle 9.3.1 wrappers verify their upstream ZIP checksum.
No Actions Gradle/npm cache is restored. Each command requires a clean checkout,
clones the exact HEAD into a new directory, and starts empty Gradle/npm cache
directories. It never imports `~/.gradle`, `~/.m2`, ignored files, `node_modules`,
old build outputs or the developer Maven repository. It runs `npm ci` and the
producer's required patch application. Standard upstream downloads are allowed.
The runner downloads the standard Gradle ZIP once with bounded retries, verifies
the wrapper's pinned SHA-256, and supplies that tooling ZIP to both wrappers and
the isolated consumer. No Gradle dependency cache is copied. Fresh test XML is
snapshotted and checked before staging's second `npm ci` removes Expo's test
output directory; old snapshots are refused.

The staged consumer creates another empty Gradle home and uses Gradle exclusive
content resolution for all `dev.dootah` groups. Its own public test certificate
is generated during that invocation. Both variants must have at least the
current expected hook counts (13 debug, 11 release) and the intended SDK version.

Before the consumer, once staging has passed and every suite report has been
snapshotted and verified, the SDK job reclaims producer-only state: git-ignored
outputs in the isolated checkout (`node_modules`, `build`, `.gradle`, the
developer Maven repository), the producer Gradle home, the npm cache and temp
files. Tracked sources, `reports/`, the stage and the verified Gradle ZIP remain;
`reports/` can never be reclaimed. A measured local run needed about 7.9 GiB of
producer state, of which 7.4 GiB was reclaimed, and the fresh consumer then
needed 4.6 GiB in its workspace (5.4 GiB of free-space loss). The consumer
therefore requires at least 7 GiB free and otherwise fails with an explicit
"Insufficient disk for isolated staged consumer" error instead of corrupting
Gradle's caches. Every stage records free disk and fixed-label directory sizes
in `result.json` or `failure.json`.

From a clean committed tree, use fresh output paths:

```sh
python3 v2/ci/run.py sdk "$HOME/dootah-evidence/sdk-new"
DOCKER_DEFAULT_PLATFORM=linux/amd64 python3 v2/ci/run.py server "$HOME/dootah-evidence/server-new"
python3 v2/ci/run.py security "$HOME/dootah-evidence/security-new"
```

The server job fetches upstream SHA
`b46e13569f5734a66ed903f75f51b87b78e80c1b` itself; only the allowlisted MIT
composition enters build contexts. Unique empty Go volumes are removed afterward.
Disposable Compose projects generate their own passwords, Cloud users/apps,
contracts, bindings, signing identities, API keys and local TLS CA. No acceptance
state, manually started service or tenant fixture is reused. Compose validates
private networking, DB roles, startup refusals, `/live` and `/ready`, signed
operations, credential rotation, restart persistence, log secrecy and restore
into different fresh volumes. Its original and restored projects are both removed.
Linux AMD64 image inspection is mandatory; this is server validation, not a new
Android/device architecture claim. Local ARM hosts can use Docker emulation;
that is not a native Ubuntu host qualification.

## Security and evidence

Workflow permissions are `contents: read`; checkout does not persist credentials.
Actions and scanner/linter source revisions are pinned to full SHAs. PRs never
use `pull_request_target`, signing secrets or a production identity. Release
inputs enter scripts through environment variables, not interpolated shell code.
These choices follow GitHub's [secure-use guidance](https://docs.github.com/en/actions/reference/security/secure-use).

`pins.json` checks reviewed important SDK/server/tool dependency inputs. Updating
a digest means reviewing the associated lockfile, dependency/license inventory
or deployment pin; it is not an automatic approval of a dependency upgrade.
The SDK stage generates the existing bundled/source/Maven license inventory;
the server retains its Go module/graph audit and reviewed license inventory.
**SBOM: not implemented.** Inventories have explicitly limited scope and are not
labelled SPDX/CycloneDX or a universal supply-chain/security audit. Container OS
package versions are not fully locked by this phase.

Gitleaks scans all tracked source without scanning ignored local configuration or
the full Git history. Five reviewed false positives are listed in
`secret-exceptions.json`, all public Expo asset content keys in sanitized evidence
receipts. Exceptions bind path, rule, line and the SHA-256 of the entire file. A
changed file or new finding fails. No blanket directory or generic PEM allowance is
used.

Command output is private and never streamed into Actions logs. The runner
uploads only allowlisted successful candidate/evidence files or a sanitized
failure receipt: the failing stage, exception type and authored validation
message, a failed command's tool/repo-relative script and exit code, and free
disk space. SDK receipts alone add a bounded excerpt (Gradle's "What went
wrong" block, failed tasks, final exception line) with workspace/home paths
replaced, credential-like lines dropped and long tokens masked; server and
security output can contain generated credentials or findings and is never
excerpted. It does not upload raw Gradle/Cloud/GPG logs, test assertion bodies,
environment files, keys, dumps, cookies, tokens or tenant data. `--keep-private`
is a local debugging option only; the printed workspace path is not an artifact
upload target. Do not share it. Without that option temporary checkouts/caches
and raw diagnostics are removed on success and failure. The acceptance harness
also removes generated credentials/backups after exporting its receipt. A hard
VM/process kill cannot guarantee application-level cleanup; hosted runners are
disposable and the workflows do not use a shared production Docker daemon.

Retained artifacts: normalized suite counts/source commit, candidate metadata,
SHA256SUMS, license inventory, allowlisted staged repository ZIP, xprem audit,
container image IDs and deployment/restore receipt. Local unpushed Docker images
have content IDs rather than registry publication digests. Ordinary artifacts
expire after 14 days; the aggregate suite receipt expires after 30 days.

## Manual signed release candidate

`v2-release.yml` validates a clean reviewed branch, full source SHA, public
version and future `vVERSION` tag intent (the tag must not already exist).
The version source remains public `0.1.0-alpha.1`, development `0.7.5-local`,
Runtime ABI 2, Logic ABI 1. It runs the entire reusable matrix again, including
restore, and only then enters the `v2-release-signing` environment.

The signed job downloads only its own run's successful SDK candidate. It checks
archive integrity, source/version intent, all POMs and marker/runtime/plugin
versions, internal immutable versions, content audit and the consumer receipt
bound to that exact Maven checksum manifest. It imports the configured key into
a temporary private GnuPG home, requires the full fingerprint and passphrase,
uses the existing immutable-coordinate ledger, signs every primary repository
artifact and verifies every detached signature. Output is a generic repository
layout ZIP usable later with Central or Artifactory, signed checksums, release
metadata and the updated ledger. Preserve the ledger; no registry is chosen.
The ledger is not silently reset: even the first release requires explicit `{}`.

Normal CI exercises GPG with a newly generated **TEST ONLY** protected key and a
small packaging fixture, then verifies the signature, rejects tampering, checks
unsafe passphrase-file refusal and deletes the key. That fixture is not SDK
consumer evidence and is never exported as a release candidate. The real release
job still requires its configured signing identity and full SDK/restore gates.

### USER ACTION REQUIRED — GitHub verification and later signing

Nothing in this section has been performed by the Phase 9E implementation.

1. Review and push the Phase 9E commits yourself. Enable Actions if disabled.
   Open a PR targeting `pivot/dootah-v2` (no signing secrets needed) or dispatch
   **V2 alpha CI** on that branch once GitHub makes the workflow available.
   Workflow dispatch may require the workflow file on the default branch; a
   push/PR can verify the branch first without changing the default branch.
2. Inspect all three jobs and the **V2 required suites** aggregate. Make that
   aggregate a required branch check after its actual check name appears. A local
   script or actionlint success is not a GitHub-hosted Actions PASS.
3. Only when a signed candidate is wanted, create the protected environment
   `v2-release-signing`, restrict it to reviewed release branches, and require a
   human reviewer. Configure repository variables `DOOTAH_DEVELOPER_ID` and
   `DOOTAH_DEVELOPER_NAME` with the public maintainer metadata used in the stage.
4. Configure environment secrets `DOOTAH_SIGNING_KEY_BASE64` (base64-armored
   private signing export) and `DOOTAH_SIGNING_PASSPHRASE`; environment variables
   `DOOTAH_SIGNING_FINGERPRINT` (40 uppercase hex characters) and
   `DOOTAH_COORDINATE_LEDGER` (retained JSON coordinate checksums, explicitly `{}`
   for the first candidate). Do this directly in your secret manager/GitHub UI;
   never send secret values to chat. Preserve prior ledgers and never erase a
   conflict to reuse immutable coordinates. Variable size limits may eventually
   require an operator-managed ledger file source.
5. Dispatch **V2 signed release candidate (no publication)** on the reviewed
   branch with `version=0.1.0-alpha.1`, `tag_intent=v0.1.0-alpha.1` and that
   branch's full source commit. Review the signing environment approval after
   all gates pass. Download, independently inspect and retain the signed export
   and updated ledger before the artifact retention deadline.

Publishing, namespace/account decisions, tags, public releases, Docker pushes,
external deployment and Phase 9F–9H remain separate user-controlled work.
