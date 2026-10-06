> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# Phase 9E — CI and release automation

2026-10-04. Starting branch `pivot/dootah-v2`, clean HEAD
`135417149f9db7b4608ece9dd5c50a40e5e29152`, containing all four expected Phase 9D
commits. `DOOTAH_CURRENT.md` was absent; the explicit Phase 9E task defined scope.

Local verification is recorded below. GitHub-hosted Actions have **not** been
executed. No workflow push, repository secret creation, tag, public release,
Maven/JFrog or Docker publication, external deployment or Phase 9F–9H work occurred.

## Delivered

The authoritative alpha entry point is [V2 alpha CI](../../.github/workflows/v2-ci.yml),
with the [reusable required gates](../../.github/workflows/v2-gates.yml) and
[manual signed-candidate workflow](../../.github/workflows/v2-release.yml).
The [operator/CI guide](../../v2/ci/README.md) gives prerequisites, exact local
commands, isolation, report rules, artifact retention, signing configuration,
immutable ledger custody and the external GitHub actions still required.
Historical V1 CI remains unchanged and is not the alpha gate.

PRs, relevant branch pushes and manual runs have no path filters. All three jobs
are required: SDK, server and security. Every run includes production Compose
acceptance and restore; no release or PR can omit them. The aggregate requires
all jobs and every [manifest suite](../../v2/ci/required-suites.json) from the same
source commit. Parsers reject missing/empty/incomplete reports, missing named
classes/Cloud tests, failures and unexpected skips. Twelve focused CI regressions
exercise these refusals, report snapshot preservation, exact-byte secret-scan
exceptions, unsafe archive paths and real ephemeral-key signing/tamper rejection.

The SDK job uses a new checkout, empty Gradle/npm homes, locked npm installation,
the maintained patch, a checksum-verified standard Gradle ZIP and a new local
Maven repository. It runs `build-sdk.sh`, required JVM/Node/Python suites and
the real Compose fixture gate, then stages the public candidate with POMs,
sources/documentation, license inventory, checksums and archive/path/secret audit.
The consumer starts another empty Gradle home and exclusively resolves Dootah
from that new stage. Both fixture and public consumer require 13 debug and 11
release hooks at the intended SDK versions. Native-health XML is snapshotted
before release staging reinstalls `node_modules`; an old snapshot is refused.

The server job fetches the pinned xprem SHA itself, uses empty unique Go volumes,
audits the allowlisted source and complete build/test graph, and builds Linux
AMD64 Cloud/xprem images. It explicitly pulls the pinned AMD64 Postgres/Caddy
images, validates Caddy with networking disabled and runs the full disposable
production-mode HTTPS acceptance. Users/apps/contracts/bindings/keys/passwords
are generated during the run. Both original and restored Compose projects and
their volumes are removed. No manually started tenant or previous acceptance
state is an input.

Actions and scanner tools have immutable source pins; workflow permissions are
read-only and checkout credentials are not persisted. PR jobs have no production
signing identity. The manual release validates reviewed source/version/tag intent,
requires all gates, enters a signing environment, checks the exact stage and
consumer manifest binding, requires signing configuration and the retained
coordinate ledger, signs and verifies each artifact, and exports generic Maven
repository layout plus metadata/checksums. It has no publishing or tag-writing
operation. The GPG passphrase is read from a private temporary file, not an argv
value; key material and raw diagnostics are never workflow artifacts.

Tracked-source Gitleaks scanning covers both V2 and V1. Seven inspected false
positives are allowed only by path/rule/line and whole-file SHA-256: five public
Expo content keys and two PEM delimiter/generated-key code snippets. Any changed
bytes or additional finding fail. The existing SDK license inventory and xprem
module/graph audit are retained. **SBOM: not implemented**; these inventories are
not mislabelled as a standardized or universal supply-chain audit.

## Local evidence

The final implementation revision under test is
`5f6e3e2b3ba6f7af0e2e8b8b14e9a225c8767550`. This later evidence-only commit does
not change the workflows or build implementation. Each job starts with a fresh
checkout and cache scope. Public package downloads are permitted. The SDK host
is macOS/ARM64 with JDK 21; server containers run Linux/AMD64 under Docker Desktop
emulation. This is not a native Ubuntu machine qualification, GitHub Actions run
or new phone/device OTA proof.

The server receipt records all 15 deployment assertions, including private
ports, non-root HTTP, DB privilege/startup refusals, independent liveness and
readiness, signed publication/rollback, credential rotation and log secrecy.
Restore uses matching backed-up secrets, two new databases and fresh storage in
another Compose project; storage hashes and original signing identity verify.
The final receipt includes successful cleanup. xprem verifies **341 byte-identical
upstream files, 837 AMD64 dependency packages, 4,071 source entries and zero EE
dependencies**. The architecture-dependent package/source totals differ from
Phase 9D's ARM64 graph; the pinned upstream input set is unchanged.

Early development iterations exposed a TAP parser treating `# todo 0` as a
directive, a Gradle download timeout, an existing ARM64 Postgres tag, macOS's
GnuPG socket path limit and native-health reports removed by `npm ci`. Those
were corrected without a runtime change. A redundant local restore attempt
timed out while an emulated Go build competed for resources on the 8 GiB host;
the final server run executes its own build and acceptance sequentially and
passes unchanged production health thresholds. The final local SDK/server work
was serialized to avoid host swapping; hosted jobs use separate runners.
Incomplete or interrupted attempts are not counted as passing evidence.

The final SDK job and [aggregate receipt](evidence/phase9e-20261004/required-suites-result.json)
pass. The public consumer executed 87 tasks with a fresh Gradle dependency cache,
resolved the plugin marker/runtime from the newly staged repository, and passed
Debug/D8 and Release/R8 with **13 and 11 hooks**. The stage validates 19 Maven
publications and has a clean content audit. Its checksum manifest SHA-256 is
`90b91aefdecaf10adff5bd502601a286984fc2072d42fbea223afa2f13542a94`.

| Suite | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: |
| SDK runtime | 7 | 0 | 0 |
| Native health | 3 | 0 | 0 |
| Gradle plugin | 9 | 0 | 0 |
| Real Compose fixtures | 4 | 0 | 0 |
| Publisher JVM (including Portable IR/Logic) | 22 | 0 | 0 |
| Portable Node | 2 | 0 | 0 |
| CLI | 7 | 0 | 0 |
| Packaging regressions | 13 | 0 | 0 |
| Server audit regressions | 2 | 0 | 0 |
| Deployment config | 10 | 0 | 0 |
| Cloud API/auth/unit/DB/signed backend | 13 | 0 | 0 |
| Complete Compose/restore acceptance | 1 | 0 | 0 |
| CI gate/security/signing regressions | 12 | 0 | 0 |
| **Dootah total** | **105** | **0** | **0** |
| Pinned xprem tests/subtests | 345 | 0 | 4 named Azure exclusions |

The four exclusions remain `TestAzuriteUploadListGet`,
`TestAzuriteSASUploadRequiresBlockBlobHeader`, `TestAzuriteCreateFrom` and
`TestAzuriteDeleteUpdate`. All 450 executed passing tests are reported separately
from these four exclusions. Gradle conditional/NO-SOURCE task statuses are not
test-suite skips. The historical V1 `dootahRealComposeCheck` is not applicable;
the V2 real Compose gate was run. No runtime/compiler behavior changed.

Sanitized [SDK](evidence/phase9e-20261004/sdk-result.json),
[server](evidence/phase9e-20261004/server-result.json),
[security](evidence/phase9e-20261004/security-result.json),
[consumer](evidence/phase9e-20261004/sdk-consumer-result.json) and
[restore](evidence/phase9e-20261004/server-acceptance.json) receipts, image IDs,
license inventory and SHA-256 manifest are retained. The unsigned candidate ZIP
is a local build artifact, not a committed binary or published release.

Implementation commits: `38f51a2`, `a0bf00d`, `cc18eb0`, `f11e99b`, `5f6e3e2`.
This documentation/evidence commit follows those focused implementation commits.

## Frozen contract and limits

Runtime ABI **2**, Logic ABI **1**, public SDK **0.1.0-alpha.1**, development SDK
**0.7.5-local**, Apache-2.0, BasicText/Option A: unchanged. No portable grammar,
capability, renderer, JavaScriptSandbox, installed signing/trust or native
fallback behavior changed. No production signing identity was used; the real
signing regression uses a generated protected **TEST ONLY** key and discards it.

GitHub scheduling, runner capacity, environment protection, secret injection and
artifact upload/download still require an actual hosted run. The local commands
and actionlint cannot establish those results. OS package versions inside the
pinned container bases are not fully locked, secret scanning covers the current
tracked tree rather than Git history, and no standardized SBOM or universal
vulnerability/supply-chain guarantee is claimed. Artifact retention is temporary;
operators must retain the release ledger and any intended release candidate.

**USER ACTION REQUIRED:** follow the exact GitHub verification/signing steps in
the [CI guide](../../v2/ci/README.md#user-action-required--github-verification-and-later-signing).
The user must review/push the commits and run Actions; no external action was
taken here. Configure signing only when a signed candidate is wanted. Registry,
domain ownership and publication decisions remain outside this phase. Do not
send any secret values back.

**Phase 9E implementation and local acceptance complete: YES.**
**GitHub-hosted Actions actually executed: NO.** The hosted verification action
above remains with the user; no hosted CI PASS is claimed. Phase 9F may begin
only when separately requested. Phase 9F is not started.
