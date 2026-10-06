> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# Phase 9B — Public artifact / package hardening

Scope: Android artifact distribution only. Runtime ABI **2**, Logic ABI **1**,
BasicText / Option A, capability contracts, sandbox and native fallback are unchanged.
No Phase 9C–9H implementation, account creation, external upload, tag, push, merge or
history rewrite occurred. This is packaging/build acceptance, not a new device OTA proof.

Starting branch: `pivot/dootah-v2`; starting HEAD:
`5b2d2b26dc24efd800ccfe45fb4be7026d25e434`. The starting working tree was clean.

Implementation commits:

- `d0c815a` — Apache licensing and original upstream license evidence.
- `0bd0f11` — alpha Maven staging, notices, metadata and immutable internal versions.
- `7ef77e5` — artifact gates and isolated consumer acceptance.
- `de211d6` — checksum-verified upstream Gradle ZIP support for isolated consumers.

The final documentation/evidence commit follows these implementation commits.

## Version and coordinate model

| Concept | Value / behavior |
| --- | --- |
| Public SDK version | `0.1.0-alpha.1` |
| Development SDK version | `0.7.5-local` |
| Runtime ABI | `2` |
| Logic ABI | `1` |
| Internal redistributed version | `0.1.0-dootah.sha256-<SHA-256>` |

The internal digest covers packaged binary/source/documentation/notice payloads,
dependency metadata, packaging implementation and configured maintainer metadata.
Different redistributed bytes cannot silently retain a release coordinate. A retained
checksum ledger also rejects changed public coordinates during signed export.
The alpha version is explicit; development versions do not become public versions.

Public API artifacts are `dev.dootah:runtime-v2`,
`dev.dootah:dootah-android-plugin`, and the POM-only plugin marker
`dev.dootah:dev.dootah.gradle.plugin`. Plugin ID remains `dev.dootah`.

Sixteen Expo artifacts are implementation details that must be published to resolve
the runtime. Eleven are built from source; five npm-provided modules are rehosted
under `dev.dootah.internal`, with corresponding POM and Gradle metadata dependencies
rewritten. Dootah does not publish into Expo-owned namespaces. Native classes,
rendering capabilities and dependency scopes remain intact. Standard React Native,
Hermes, AndroidX and other Maven dependencies retain their upstream coordinates.

Producer npm/Metro/Gradle tooling, the producer APK and xprem are not SDK publications.
The release repository contains exactly **19 publications**: 17 AARs, one plugin JAR,
and one POM-only marker, with 18 sources and 18 documentation JARs. It contains 91
primary/metadata files covered by a sorted SHA-256 manifest, plus checksum sidecars.

## License and artifact evidence

Root [LICENSE](../../LICENSE) is the official Apache License 2.0 text. It covers
Dootah-owned code, not third-party code. [NOTICE](../../NOTICE) states that boundary
without inventing a copyright holder or employer. Upstream copyright statements
and licenses remain intact.

The [inventory](evidence/phase9b-20261003/inventory.json) contains 226 records:
26 source-map-proven JavaScript packages, 16 rehosted Android components, four
bundled native component records, six native header dependencies, and 174 resolved
Maven transitive records across plugin/runtime graphs. Repeated components in
different roles are intentional; this is not a count of unique libraries.

Evidence includes exact package manifests/licenses, npm lock inputs, Metro source
maps, Maven POMs and inherited POM licenses, the pinned React Native version catalog,
and Ninja compiler dependency databases. The latter identify **660 prefab header
inputs**. Native notices cover bspatch, bzip2, fbjni, NDK toolchain notices, Folly,
Boost, fmt, glog, double-conversion and fast_float, including copyright/license
preambles from compiled headers. Conservative NDK notices do not imply every listed
NDK component was linked. Javax Inject 1's missing POM license is resolved from its
original Central sources JAR header, with the evidence source recorded.

Each staged archive includes `META-INF/dootah` notices/inventory. AARs additionally
carry `assets/dootah-licenses`, including Dootah's Apache license, for Android asset
merging. Source JARs preserve upstream comments and include native source inputs
where AGP's default sources artifact omits them. Expo Updates retains MIT licensing,
its exact maintained patch, and the patch's modification description. Existing Expo
Constants producer-config sanitization remains `{}`; producer identity is not shipped.

The [content audit](evidence/phase9b-20261003/audit.json) has no findings. It checks
nested archives for local paths, private keys, credential patterns, debug signing
material, known E2E identities and commercial `ee/` entries. The only allowed PEM
is Expo Updates' unmodified **public** root certificate, matched by the exact file
SHA-256 `901e7b0da287ca154fc09d982d37769b09ee291255b239265b5b0a8b75165baa`.
No private signing key is allowed by that exception.

The [class inventory](evidence/phase9b-20261003/classes.json) contains only expected
Dootah/Expo namespaces and the reviewed React Native compatibility classes.
A separate [payload comparison](evidence/phase9b-20261003/payload-preservation.json)
confirmed preservation of 2,756 existing archive entries, including all **2,670 class
entries** and **five native libraries**, across the 18 primary binary artifacts.
xprem is not an Android build input and commercial xprem EE content is **ABSENT**.
This is an evidence-backed audit of this distribution, not a universal legal-compliance
claim for future versions or applications built with additional dependencies.

## Publication readiness

Maven Central is recommended for runtime, required internals, plugin implementation
and marker. Consumers use `google()` and `mavenCentral()` for dependencies and add
`mavenCentral()` to `pluginManagement.repositories` to resolve `id("dev.dootah")`.
A Plugin Portal listing is optional; no Portal-specific plugin or account is required
for this consumption model.

POMs include names, descriptions, project URL, SCM, correct license metadata and
resolvable dependencies. Public maintainer ID/name are configurable through
`DOOTAH_DEVELOPER_ID` and `DOOTAH_DEVELOPER_NAME`. They are intentionally absent in
the unsigned acceptance candidate because personal details were not supplied.
Signed export fails until they are configured; no identity was invented.

Documentation artifacts contain a nonempty HTML source reference, original source
comments and the SDK integration guide, under the standard `javadoc` classifier.
They are explicitly identified as source references, not generated Java Javadoc.
The marker requires neither sources nor documentation artifacts.

[Staging and export instructions](../../v2/android-sdk/release/README.md) describe
unsigned local staging and the separate signing-required export. Signing uses
`GPG_KEY_ID`, optionally `GNUPGHOME`, and the user's local GnuPG agent/pinentry.
Every detached signature is verified before the export ZIP is created. The export
requires matching artifact checksums, a clean-consumer receipt for those exact bytes,
complete maintainer metadata, and an immutable coordinate ledger. Concurrent exports
using the same ledger are locked. No private key/password is accepted through source
files, committed configuration or chat.

Signing orchestration and failure gates are unit-tested. Real identity-key signing
and Sonatype's server-side validation have **not** been performed; those require
user-controlled external setup. The stage is unsigned and no upload code is provided.

## Validation

The final stage was produced from a clean detached checkout at `7ef77e5`: all **425**
Android producer tasks and all **13** plugin publication tasks executed. It did not
read the existing development Maven repository. Only consumer-test tooling changed
in `de211d6`; the staged artifact-producing code is identical.

The local stage is `v2/android-sdk/build/release-stage-final/repository` (ignored build
output). Its retained [SHA256SUMS](evidence/phase9b-20261003/SHA256SUMS) manifest has SHA-256
`8f26fc3ef1346e6e3eaa1dfec3d6b18251756c4e7d033c6779e4d83432bb27d7`.
The runtime AAR SHA-256 is
`99b66d848d39493d0509f2ddebe1bedc72b55afc58ef6cca1fa5f2a21f7c16e6`.
ZIP timestamps are normalized; byte-for-byte reproducibility across builds is not claimed.

| Required / relevant suite | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: |
| Runtime unit tests | 7 | 0 | 0 |
| Native health tests against patched Expo Updates | 3 | 0 | 0 |
| Gradle plugin tests | 5 | 0 | 0 |
| Real Compose fixture tests | 4 | 0 | 0 |
| Publishing/analyzer JVM tests | 18 | 0 | 0 |
| Packaging, integrity and signing-gate tests | 13 | 0 | 0 |
| Publisher portable + Cloud core + server audit precondition tests | 7 | 0 | 0 |

`build-sdk.sh` and the fixture gate pass. The fixture finds 13 debug and 11 release
entry points. Normal Gradle `NO-SOURCE`/conditional task statuses are not skipped test
suites. Compiler/renderer/runtime behavior was not changed, so this phase does not
claim a new real-device OTA proof or require a new installed capability contract.

The first isolated staged consumer passed Debug/D8 and Release/R8, with all 87 tasks
executed. The final clean-checkout candidate also passed both builds with all **87** tasks
executed and 13 debug / 11 release hooks; its independent [consumer receipt](evidence/phase9b-20261003/consumer-result.json)
is bound to the exact retained artifact manifest. Each consumer starts in a new temporary workspace with an empty
`GRADLE_USER_HOME`, uses an exclusive Dootah repository filter, resolves the normal
plugin marker, and verifies hook counts and the public SDK version.

The final run's Java wrapper downloader timed out fetching the upstream Gradle ZIP.
The helper now accepts optional `DOOTAH_GRADLE_DISTRIBUTION_ZIP`; it verifies the ZIP
against the pinned official checksum in the fixture wrapper before using it. This
only supplies the upstream Gradle tool distribution. It does not reuse a Gradle
module cache, local Maven repository, Dootah JAR/AAR or consumer build output.
The default path still downloads Gradle normally. The final receipt records which
path was used.

## USER ACTION REQUIRED

1. Create a [Central Portal account](https://central.sonatype.com/). No account or
   credentials have been created here.
2. [Verify the `dev.dootah` namespace](https://central.sonatype.org/register/namespace/)
   using the Portal-provided DNS TXT record for `dootah.dev`. If you do not control
   that domain, namespace ownership remains a blocker; do not claim it or silently
   change coordinates.
3. Choose the public maintainer ID/name and supply `DOOTAH_DEVELOPER_ID` and
   `DOOTAH_DEVELOPER_NAME` locally when creating a new stage.
4. Install GnuPG and independently create or select your signing-capable OpenPGP key.
   Keep private material in your own keyring/secret manager. Publish only its public
   key through a supported keyserver following
   [Central's PGP instructions](https://central.sonatype.org/publish/requirements/gpg/).
   Set `GPG_KEY_ID` to the intended fingerprint, optionally set `GNUPGHOME`, and unlock
   it locally using GnuPG's agent/pinentry. No key tied to your identity was generated.
5. Restage with the public metadata, rerun the isolated consumer, then use `sign.py`
   to produce a local signed ZIP. Retain/back up the coordinate ledger. These are
   local actions; signing does not upload anything.
6. For a later explicitly authorized manual release, Central Portal accepts the
   repository-layout ZIP through its [upload UI](https://central.sonatype.org/publish/publish-portal-upload/).
   No API token is needed by this manual workflow. A future API uploader would need
   a Portal user-token username/password, kept in your secret store. A Plugin Portal
   account is unnecessary unless you separately choose to list the plugin there.
7. Do **not** share private keys, passphrases, Portal passwords, tokens or recovery
   material. Send back only namespace-verification status, chosen public maintainer
   metadata and the public signing-key fingerprint.

External publication: **NOT PERFORMED**. Phase 9C is not started.

## Final Phase 9B result

| Gate | Result |
| --- | --- |
| Dootah-owned license metadata | PASS |
| Third-party notice audit | PASS for the audited distribution and recorded evidence |
| Expo/rehosted artifact licensing | PASS; upstream licenses retained |
| Internal artifact immutable versioning | PASS |
| Plugin publication metadata | PASS; public maintainer metadata configurable |
| Runtime publication metadata | PASS; public maintainer metadata configurable |
| Sources artifacts | PASS; 18 JARs |
| Documentation artifacts | PASS; 18 nonempty source-reference/guide JARs |
| Artifact signing support | PASS; real-key signing not performed |
| Staged Maven repository | PASS; 19 publications from a clean checkout |
| Fresh consumer resolves Dootah from staged repo only | PASS |
| Debug/D8 consumer | PASS |
| Release/R8 consumer | PASS |
| Fixture tests | 4 passed, 0 skipped |
| Artifact SHA-256 manifest | PASS |
| Artifact secret/path scan | PASS; hash-pinned upstream public certificate exception documented |
| Commercial xprem EE content | ABSENT |
| External publication | NOT PERFORMED |

Recommended public publication target: **Maven Central**, including the plugin marker;
Plugin Portal listing is optional. All required test suites ran with **0 failures and
0 skipped tests**. `git diff --check` passed before the commits.

Remaining Phase 9B blockers: user-controlled namespace/account setup, chosen public
maintainer metadata, and local signing-key configuration before a signed public
candidate can be exported and validated by Central. No remaining agent-side package
implementation or local acceptance blocker is known. Actual Central acceptance and
real-key signing are not claimed by this unsigned local proof.

Is Phase 9B agent-side work complete? **YES**.

Can Phase 9C begin after the user completes the required external setup? **YES**.

Stop at Phase 9B. Phase 9C has not been started.
