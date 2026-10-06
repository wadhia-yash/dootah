> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# Phase 9C — CLI / onboarding hardening

2026-10-03. **PASS.** Starting branch `pivot/dootah-v2`, clean starting HEAD
`d54db5b59c0bbe59f059f7caa5f2c2850f995e41` (completed Phase 9B).
`DOOTAH_CURRENT.md` was absent; the explicit Phase 9C task supplied the acceptance criteria.

Implementation commits: `1748741` (retention and debug HTTP guard), `3351989`
(standalone customer CLI and contract registration). The acceptance/evidence commit
follows these. No Phase 9D–9H work, external deployment or public registry publication
occurred. Public SDK `0.1.0-alpha.1` and development SDK `0.7.5-local` are unchanged.

## Delivered workflow

The [customer guide](../../v2/publishing/ONBOARDING.md) is included in the installable
CLI directory. Build the tool once with its own wrapper, copy the distribution, and
run it with JDK 21 / Node 24. Execution has no native-consumer, repository-wrapper,
fixture, npm/Metro or copied-helper dependency.

The Gradle application plugin supplies `dootahRetain<Variant>Release`. It builds the
native APK and reads public AGP APK/class/source artifacts. The record retains the
APK and hash, pre-R8 project classes and deterministic hashes, actual hooks, installed
capability asset, bounded variant Kotlin snapshots and toolchain/target metadata.
Repeated capture of identical inputs is deterministic; an actual APK rebuild can
change the pre-existing embedded identity/timestamps. The task does not claim device
attestation or byte-reproducible APK builds.

`release import` verifies the retained inputs and creates/pins the source-bearing local
contract. `env create` / `contract register` derive the public function-type contract
from installed descriptors/nullability, validate ABI 2 / Logic ABI 1, and call the normal
authenticated Cloud API. Full source contracts never leave the machine. A matching
existing environment is accepted idempotently; mismatched app, environment, runtime
or contract is rejected. Cloud's existing immutable environment semantics mean that
creation and registration are combined **after** the native build; the channel name
is selected before the build.

The CLI covers login/logout, org/app creation and listing, environment listing,
scoped token creation/listing/revocation, analysis/publication, release/operation
inspection, rollout/pause/resume, signed rollback, and enrollment-ticket issuance.
Organization selection uses the explicit organization flag/environment variable.
All Cloud authorization, CSRF, token scopes, revocation, optimistic release versions
and operation idempotency checks remain authoritative. Credentials use environment
input or explicitly requested mode-0600 files; secrets are not printed. Session files
are origin-bound and short-lived. There is no automatic global credential store.

Telemetry has a backend-only ticket helper and a generic native Application example.
The backend still authenticates the app user and keeps its scoped operator credential;
native code receives only the existing ten-minute single-use ticket and exchanges it
through the unchanged SDK. No APK operator secret, anonymous enrollment, portable-code
enrollment or fake zero-code integration was introduced.

Local HTTP has a non-debuggable-build guard and a separate `debugUpdateUrl` setting.
The fixture and guide use a debug-only Network Security Config allowing explicit local
hosts while denying other cleartext. The plugin does not overwrite a consumer's custom
network policy. No global production `usesCleartextTraffic="true"` recommendation remains
in the updated integration example.

## Acceptance and tests

The repository-owned [acceptance harness](../../v2/publishing/acceptance.py) ran from a
fresh temporary workspace with disposable PostgreSQL 17, the existing audited xprem
composition, isolated signing/storage state and a loopback Cloud process. It provisions
only infrastructure/users/delivery bindings through the existing operator mechanisms.
All customer org/app/environment/token/release state is created through normal APIs
using the copied CLI. No custom out-of-repository helper or manual customer DB edit
is used. Existing DB security tests intentionally probe database constraints; these
are separate from the customer onboarding flow.

The fresh application uses Phase 9B's staged runtime/internal artifacts through an
exclusive Maven repository filter, with the changed plugin built from source as a
Gradle composite. The 9B stage was not overwritten or rebuilt. Its manifest SHA-256
remains `8f26fc3ef1346e6e3eaa1dfec3d6b18251756c4e7d033c6779e4d83432bb27d7`.

Two independent organizations/apps passed customer setup, Debug retention/import,
automatic registration and duplicate detection, mismatch refusals, scoped-token
denial/revocation, normal Kotlin edit, analysis, signed Cloud publication, release
inspection, 25% and 100% rollout, pause/resume, signed rollback and enrollment-ticket
issuance. The rollback operations reported `signatureVerified: true`. A separate R8
build retained the pre-R8 classes and imported the actual minified APK successfully.
The sample uses one installed String-input composable; no human supplied its identity
or parameter descriptor. Configuration cache stored/reused successfully.

The final complete run returned a successful receipt after forcing Node's TAP reporter
for machine-readable no-skip checks. Earlier development runs caught and fixed an empty
Groovy-map check and harness-only provisioning/readiness/origin/reporter issues. Final
results, not those incomplete runs, are recorded below.

| Suite | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: |
| Gradle plugin / retention / local HTTP | 9 | 0 | 0 |
| Real Compose fixtures | 4 | 0 | 0 |
| Publisher JVM / contract derivation / bounded sources | 22 | 0 | 0 |
| CLI / portable / server-audit Node tests | 11 | 0 | 0 |
| SDK runtime | 7 | 0 | 0 |
| Native health | 3 | 0 | 0 |
| Packaging regression tests | 13 | 0 | 0 |
| Cloud API / auth / scope / enrollment / DB / signed backend | 13 | 0 | 0 |
| Isolated complete CLI flow | 1 | 0 | 0 |
| **Total** | **83** | **0** | **0** |

The real Compose gate also built Debug/D8 and Release/R8: 13 and 11 fixture hooks.
Normal Gradle conditional/NO-SOURCE task statuses are not skipped test suites.
The V1 `dootahRealComposeCheck` task is not applicable: no compiler or Compose rendering
behavior changed. V2's real Compose gate ran instead. Runtime implementation/binaries,
ABI 2 / Logic ABI 1, Portable grammar, capability contracts, BasicText renderer,
JavaScriptSandbox and signing/trust model are unchanged. No new device OTA claim or
phone rerun was needed or performed.

Sanitized [acceptance evidence](evidence/phase9c-20261003/acceptance.json) contains input
digests, build metadata, command coverage and counts; [Cloud TAP](evidence/phase9c-20261003/cloud-tests.tap)
records all 13 enabled tests. Raw logs, APKs, source/class records and short-lived
credentials remain in a private temporary workspace outside Git. Final test containers
and their network were removed. No passwords, tokens, private keys or APKs are committed.

## Remaining alpha bounds and next-phase prerequisites

- Retention accepts one APK output per variant and application-module-contained Kotlin
  roots. Archive its output before the next native build. It is a trusted local release
  record, not proof of installation. Multi-output APK selection and external source-root
  mapping are not claimed.
- The CLI is source-built for alpha; credential-file checks target POSIX ownership/modes.
  Public tool/SDK distribution is still unpublished. The preserved 9B stage lacks the
  new plugin tasks, so the source-built plugin is used during this pre-publication phase.
- Cloud requires operator-provisioned users and per-app signing/storage delivery bindings.
  A delivery backend's advertised origin must be the Cloud gateway origin, while its
  private binding uses the upstream origin. Customer CLI commands do not provision
  production infrastructure or bypass these operator boundaries.
- Telemetry requires an authenticated customer backend/native integration and renewal
  tickets. There is no anonymous enrollment or automatic permanent credential renewal.
  Environment contracts remain immutable, and publication/rollback completion remains
  asynchronous and explicitly inspectable.

No remaining Phase 9C acceptance blocker is known. Future production work must supply
deployment/operator configuration and secret lifecycle decisions; those are prerequisites
for later phases, not implemented here. Phase 9B's external publication/account/signing
decisions also remain separate.

**Phase 9C complete: YES. Phase 9D may start when separately requested: YES.**
Stop at Phase 9C; Phase 9D is not started.
