> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# Phase 9F — public OSS documentation and repository experience

2026-10-04. Starting branch `pivot/dootah-v2`, clean HEAD
`48c6c56a57b6bbbf420578c93ea9ffbc37b99d22`. The requested initial status, HEAD and
15-entry log checks confirmed all six expected Phase 9E commits. `DOOTAH_CURRENT.md`
was absent; the explicit Phase 9F task supplied scope. No subagents were used.

## Delivered

The [root README](../../README.md) now presents V2. The [public index](../README.md)
leads to a complete [quickstart](../getting-started/quickstart.md), source-derived
[support matrix](../reference/support.md), [platform limits](../reference/platform.md),
[security model](../security/README.md), [self-host overview](../self-hosting/README.md),
architecture/delivery references and [contributor guide](../../CONTRIBUTING.md).
The existing detailed operator guides remain canonical for CLI private-file/enrollment
semantics, deployment and CI operations. Stale staging instructions and CI example
paths were corrected. Pre-publication source staging is explicitly separate from
prospective post-publication repository setup.

The [minimal example](../../v2/examples/basic-text/README.md) has a baseline and OTA
version of the same ordinary Kotlin function. Its local discount helper changes
from 10 to 20; imports, signature and source skeleton stay fixed. The quickstart
includes native development-only ticket entry so the example's baseline can enroll.
Production authenticated ticket delivery and renewal remain integrator responsibilities.

Public SDK **0.1.0-alpha.1**, development SDK **0.7.5-local**, Runtime ABI **2**, Logic
ABI **1**, BasicText / Option A, Apache-2.0, installed capabilities, portable grammar,
renderer, sandbox, signing/trust and native fallback semantics are unchanged.

## Repository boundaries

The archive branch `archive/dootah-v1-pre-v2` exists locally. Historical root build,
V1 compiler compatibility workflow and old guides are explicitly labelled. The three
build/workflow files received **two comment lines each only**; executable configuration
and triggers are byte-identical after those prefixes. Broad physical V1 removal was
not performed. The [precise deferred cleanup](../history/README.md#v1-preservation-and-deferred-cleanup)
requires separately authorized 9H work, archive/remote verification, dependency review
and subsequent V2 gates. V1 automation remains active until then; it is not the V2 gate.

Dated V2 records keep their paths and content beneath historical banners, with direct
current-guide pointers on the old SDK/security/portable references. Curated evidence
JSON/binaries were not changed. Public Markdown has **zero machine-specific paths**
under the [explicit maintained inventory](../development/public-docs.json). Remaining
paths occur in labelled acceptance/history, legacy coverage scripts or security
scanner/adversarial test fixtures; they are not presented as customer setup.

Public documentation is addable normally. `.gitignore` now clearly separates private
planning from public docs; new evidence/validation files require deliberate individual
review/force-add while tracked curated evidence remains versioned. Build products,
private keys, environment files and local operator/publisher state remain protected.
No secrets, raw logs, APKs, retained customer source/classes or operator state were added.

## Verification actually rerun

Host: macOS/ARM64, JDK 21.0.11, Node 24.20.0, Gradle 9.3.1; Android SDK 36.

| Check | Result |
| --- | --- |
| Public Markdown local links/fragments, classification, paths, SDK/ABI and CLI spellings | PASS, 23 maintained documents |
| Validator failure-path regressions | 4 passed, 0 failed/skipped |
| Publisher JVM (`test installDist --rerun-tasks`) | 22 passed, 0 failed/skipped |
| Customer CLI and portable Node tests | 9 passed, 0 failed/skipped |
| Packaging regressions | 13 passed, 0 failed/skipped |
| Exact baseline/OTA source through analyzer, LogicLowering and PortableProgram | PASS, stable identity/skeleton and results `Discount: 10` / `Discount: 20` |
| Exact quickstart Gradle/Kotlin/XML snippets in temporary Android app | PASS, Debug retention and Release build, one hook in each |
| Standalone CLI retained-release import, then supported Kotlin edit analysis | PASS, one changed OTA-capable function |
| Existing tracked-source Gitleaks scanner including new docs/tools | PASS, only the 7 existing exact-byte reviewed exceptions |
| Ignore-policy probes and `git diff --check` before each commit | PASS |

There are **48 passing test cases** across the four rerun test suites, with no failed
or skipped cases. Build checks are separate from those counts. The first Android
check built successfully but the new documentation harness read the Debug hook report
from the ordinary assemble path instead of the retained path; the harness was fixed
and the full snippet/import/analysis check rerun successfully. No product fix resulted.

The snippet build used the retained Phase 9E public candidate from implementation
`5f6e3e2b3ba6f7af0e2e8b8b14e9a225c8767550`, validating its 19 Maven publications.
Its checksum manifest SHA-256 was
`90b91aefdecaf10adff5bd502601a286984fc2072d42fbea223afa2f13542a94`, matching the
existing receipt. The test generated/discarded its own public certificate/private
key, imported its freshly built APK and removed its private workspace. It used the
normal local Gradle cache: no clean-cache or newly staged artifact claim. Release
used the documented default build configuration; this check does not claim R8 coverage.
The prior required-gate receipt, not this check, supplies fresh-cache/R8 evidence.

Reproduce with [documentation validation commands](../development/README.md).
The full prior **450-test** SDK/server/security matrix was **not rerun**: runtime,
compiler, grammar, capabilities, executable Gradle configuration, deployment logic,
required-suite manifest and V2 workflow behavior did not change. Documentation and
new validation tooling were checked directly; packaging tests cover the release-guide
change included in documentation artifacts. The historical V1 real-Compose task is
not applicable. No new phone/emulator OTA proof or deployment acceptance is claimed.

## Limits, external actions and next phase

GitHub-hosted V2 Actions are **not verified**. The previous record says they have not
executed; this task did not obtain a hosted success receipt, run Actions or add a badge.
The [CI verification instructions](../../v2/ci/README.md#user-action-required--github-verification-and-later-signing)
remain outstanding. Documentation checks are explicit local commands, not newly wired
required Actions jobs. External link availability is not checked by the offline validator.

No Maven/JFrog upload, infrastructure deployment, domain configuration, release/tag,
push or history rewrite occurred. Public Maven availability is not claimed. No private
security reporting endpoint or response SLA is invented; contributor guidance states
that a private channel must be established before sharing vulnerability details.

Known remaining repository work is the deliberately deferred V1 physical cleanup and
legacy workflow retirement. Public guides link evidence with dated historical limits;
they do not retroactively claim those limits were all resolved. Operator-run hosting,
enrollment, unthemed output and single-device evidence remain alpha constraints.

Implementation/documentation commits: `2527a87`, `85cb347`, `dff32f7`. This acceptance
record follows them. **Phase 9F complete: YES.** Phase 9G can begin only on a separate
explicitly scoped request, with hosted verification/publication prerequisites assessed
against that task. No Phase 9G or 9H work was started or authorized by this record.
