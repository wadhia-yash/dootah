> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# Kotlin source publishing — Phase 5

**Current tooling (Phase 9C):** use the [customer onboarding guide](../../v2/publishing/ONBOARDING.md)
for the standalone CLI, Gradle release retention, automatic Cloud contract registration,
authentication and release controls. The historical manual retention and repository-local
launcher instructions below describe earlier acceptance evidence, not the current workflow.

2026-09-28: **PASS** for source-driven constant native text OTA on the existing
Phase 4 APK. No annotations, handwritten OTA JS, manual function IDs, Android
compiler hooks or APK reinstall were used. Scope and refusals are specified in
[Portable IR V1](PORTABLE_IR.md).

## Developer workflow

Once the release operator has imported the installed contract and configured the
publisher, edit normal Kotlin/Compose bodies and run:

```sh
v2/publishing/dootah analyze /path/to/dootah-publish.json
v2/publishing/dootah publish /path/to/dootah-publish.json
```

`analyze` is a dry run. `bundle` additionally generates the Hermes export without
contacting xprem. `publish` analyzes, validates, generates IR/JS/Hermes, checks engine
compatibility and publishes through unchanged `v2/server/publish.mjs`. Output lists
functions, changed/portable/native status and reasons, runtime/channel, evidence
directory and release ID. A changed unsupported function refuses the whole release.
No changed functions means no publication.

The command is an independent Gradle/JVM tool followed by a Node CLI; it is not a
consumer Gradle task. JDK 21 and Node are required on the publishing machine.
The existing locked producer dependencies in `v2/runtime-spike/node_modules` supply
Metro/Hermes. SDK maintainer setup from Phase 3 already installs them; on another
publishing machine use that producer's locked `npm ci`. Consumer Android sources
still need no package.json, npm tree, Expo config or JS. This is repository-local
tooling, not a public packaged CLI distribution.

## Importing an installed release

Import is a separate release-operator operation, **before editing source**:

```sh
v2/publishing/dootah import /path/to/dootah-publish.json
```

Example private local configuration (paths resolve relative to the configuration):

```json
{
  "apk": "release/accepted.apk",
  "classes": "release/instrumented-classes",
  "classHashes": "release/accepted-class-hashes.json",
  "sourceRoot": "consumer/app/src/main/java",
  "contract": "release/installed-contract.json",
  "credentials": "private/xprem-credentials.json",
  "outputRoot": "build/dootah-publish"
}
```

Retain the accepted APK, pre-R8 instrumented classes, a JSON map of their relative
paths to SHA-256 values, and the source snapshot from the same Android build. The
class directory is an explicit input; the publisher does not discover or hardcode
Android/Kotlin task names. Retain these as trusted release records, not repository
build outputs. For this continuation, Phase 4's saved class hashes and still-matching
class outputs supplied that provenance; the pulled installed APK matched its record.

Import reads literal APK manifest target configuration and the embedded identity,
hashes the APK, reads its Hermes bytecode version, checks class hashes, compares
source identities with actual hook constants and records which survive in DEX.
It writes the contract and adds its digest, package, APK digest, runtime, app and
channel to the local configuration. No function ID entry is required.

Subsequent analysis verifies that pin and the actual retained APK. A modified
contract/APK, unknown ID, runtime/ABI mismatch or changed source structure refuses
publication. The baseline contract is not refreshed after each OTA: each release
contains all supported differences from the installed native baseline. Reverting
one body to baseline omits its override; use signed rollback when restoring the
entire embedded implementation.

Credentials remain in a separate private JSON file used by the existing xprem
adapter. Do not commit credentials, contracts containing private source, exports,
APKs or raw logs. The local device proof uses `allowLocalHttp: true` for the installed
loopback endpoint; the publisher otherwise requires HTTPS. Production should give
each incompatible installed capability contract a different runtime version.

## Isolation from Android builds

No Android build files, SDK runtime, Phase 4 visitor, normal Kotlin/Compose compiler
configuration or Android task dependencies were changed. The publisher writes only
its own output directories and the explicitly requested import configuration.

A fresh isolated copy of the consumer, with an unsupported but valid Kotlin edit,
passed `assembleDebug assembleRelease --no-daemon --no-build-cache`: **85 tasks
executed**, D8 and release R8 succeeded in **2m 18s**. Failing wrappers for `node`,
`npm`, `npx`, `yarn`, `pnpm`, `metro` and `dootah` were first on PATH; their invocation
log remained absent. The analyzer project was not in that isolated copy. These
diagnostic APKs were never installed. Android/Maven caches were reused.

A publishing syntax error is isolated to publishing; invalid Kotlin itself is
still rightly rejected by Kotlin compilation. The build-independence proof uses
valid Kotlin whose behavior the portable analyzer cannot represent, not invalid
Kotlin that an Android compiler should accept.

## Physical proof, 2026-09-27–28

Samsung **SM-E426B**, Android 13, arm64-v8a. Package `dev.dootah.consumer`,
versionCode **1**, versionName **0.4.0**, runtime **dootah-v2-hook-1**.
Installed timestamp remained **2026-09-27 19:47:26 IST**.

APK SHA-256 before and after Phase 5:
`778b3414da61bcabc1f1a3e043c46b70bfb4cd364b06764f303f31ac791c3068`.
Embedded update: `421a1346-fe3b-4bbf-a04b-9b9093261d97`.
This is the accepted minified Phase 4 APK; **no Phase 5 installation occurred**.

The first real source edit changed `CheckoutScreen` from its native Material text
body to ordinary foundation `BasicText("Checkout via Kotlin OTA")`. The analyzer
found the declaration and generated its ID from source, then verified installed
agreement. It generated IR and JS; Metro/Hermes generated the artifact. No human
entered an ID or wrote the accepted JS.

The second edit changed `ReceiptScreen` to local constants and a constant `if/else`,
rendering `Receipt via Kotlin OTA`, and changed only `Overloaded(String)` to
`String overload via Kotlin OTA`. `Overloaded(Int)` and all other unchanged
functions received no overrides. The consumer source retains these accepted edits.

| Stage | Observed result |
| --- | --- |
| Baseline, backend unavailable | Native checkout/receipt/overloads; embedded frame health |
| First generated OTA download | Previous native implementation remains active |
| First activation | `Checkout via Kotlin OTA`; all other functions native; remote frame health |
| Online restart | Same generated checkout override and health |
| Offline cached restart | Package networking disabled and reverse removed; failed network check, cached checkout renders and acknowledges health |
| Multiple-target download | First checkout release remains active |
| Multiple-target activation | Checkout, receipt and String overload overridden; Int overload, List/generic/default/member/state paths native |
| Signed rollback delivery | Three overrides remain active until next launch |
| Rollback activation | Every original native implementation and embedded UUID restored |
| Further rollback restart, backend stopped | Original native UI and embedded frame health remain safe |

Every stage asserts native UI text, launched identity, actual native frame and Expo
renderer acknowledgement, unchanged installation time and absence of fatal/verifier/
Compose errors. Host checks independently verified manifest/directive signatures
and launch-asset SHA-256; device logs recorded signature verification and completed
downloads. No data clear, client database mutation, signing bypass, synthetic RN
render acknowledgement or React surface was introduced.

| Release | Identity |
| --- | --- |
| Checkout | ID `17905226755612`, UUID `091c5eeb-f800-680d-1ac2-4e48777ff7e3` |
| Three functions | ID `17905228782652`, UUID `6c874265-954f-563f-b632-25d0aa758502` |
| Signed rollback | ID `17905367091682`, commit time `2026-09-27T19:18:29.169Z` |

The second publication completed before the session interruption; it was downloaded
and activated after continuation. This pause did not replace the APK or manufacture
a new baseline. Final hardening added an installed Hermes bytecode-version check
(version **98**) and a retained-APK hash check. Re-export with those checks reproduced
the accepted IR and generated JS; Hermes debug paths prevent binary reproducibility.
No runtime changes were needed.

## Failure evidence

CLI probes refused syntax errors, unsupported changed List behavior, unknown target
identity, IR ABI mismatch, stale APK pin, modified contract digest and signature/
source-structure changes. An actual `publish` call with the unsupported edit refused
before export/network access. Unit tests reject malformed generated payloads before
execution/export. A valid publish attempt with the server stopped generated its
artifact and failed without a receipt; native rollback restart and the independent
Android build remained unaffected. No incorrect release was published by these probes.

The independent Android build contained the same unsupported List edit. The accepted
phone showed the original unsupported text throughout the accepted OTA stages.
This demonstrates refusal plus native preservation, not partial transplantation of
unsupported source. The publisher does not catch or hide Android compilation errors.

## Durable evidence and reproduction

[Acceptance record](evidence/phase5-20260928/acceptance.json) includes source/ID
agreement, releases, generated IR/JS hashes, safety results, build results and device
assertions. Curated [first IR](evidence/phase5-20260928/first-ir.json) and
[multiple-target IR](evidence/phase5-20260928/multiple-ir.json) are intentional schema
fixtures, not executable exports. Screenshots:
[baseline](evidence/phase5-20260928/baseline.png),
[Kotlin OTA](evidence/phase5-20260928/first-active.png),
[offline](evidence/phase5-20260928/first-offline.png),
[multiple targets](evidence/phase5-20260928/multiple-active.png),
[rollback](evidence/phase5-20260928/rollback-active.png).

Run focused host regressions with JDK 21:

```sh
v2/native-consumer/gradlew -p v2/publishing test
node --test v2/publishing/portable.test.mjs
```

Raw captures, private local configuration, retained APKs/classes, analyzer refusal
copies and generated exports remain under `/tmp/dootah-v2-phase5`. No publisher key
or private PEM is in the curated evidence. Networking is restored, the deny chain
disabled, reverse removed and screen timeout returned to five minutes. The existing
`dootah-v2-server-phase2c` container is stopped; PostgreSQL and persistent releases/
rollback/assets remain. The accepted main APK remains installed with native rollback
active. No V1 or completed-phase acceptance matrix was rerun.

## Remaining limits and next phase

This is deliberately not arbitrary Kotlin publishing. See the exact supported and
frozen subset in [PORTABLE_IR.md](PORTABLE_IR.md). Release provenance and runtime
version discipline remain operator responsibilities; the local contract pin is not
a fleet attestation service. No public CLI distribution or multi-app capability
matrix is claimed. In this historical Phase 5 baseline, broader inherited RN/Expo exposure and Phase 2C's cache risk remained.

Phase 6 starts with constrained installed capabilities and deterministic portable
logic, as specified in [the Phase 6 starting point](PORTABLE_IR.md#exact-phase-6-starting-point).
The 2026-09-28 [Phase 6 investigation](SECURITY_MODEL.md) demonstrated that an
authenticated publisher can bypass this analyzer and publish signed JS that reaches
the installed RN/Expo native registries. Signature verification and safe native UI
fallback both worked, but did not constrain those operations. Phase 6 stopped at
that architectural gate; the current publishing path is not a remote-code sandbox.
That initial investigation did not add business-logic support. The continuation below replaces the unsafe execution path.

## Phase 6 secure publication path

The preceding Phase 5 procedure/evidence remains historical. New publication requires
an installed ABI 2 contract; an ABI 1 target is refused because its APK has no native
capability boundary. Import/analysis and the legacy generator remain useful for
reading retained Phase 5 evidence; they do not make that runtime safe.

Use the same separate `dootah import|analyze|bundle|publish` command and config. Import
now reads `assets/dootah-capabilities.json` from the retained APK and recognizes
`ComposeEntry.tryRenderV2` identities in retained instrumented classes, checking their
presence in the packaged DEX. Source signatures/call sites stay pinned; only recognized
composable bodies (including their local pure helpers) may change. No consumer build
task runs the publishing analyzer.

For ABI 2, analysis emits typed Portable IR and generated JS evidence. The publisher
validates the exact installed capability contract using the same `PortableProgram`
Java compiler used in the APK. Export contains `program.json`, a compact bounded IR
launch artifact, plus transport metadata marked `bundler: dootah`. `generated-js.json`
retains the lowering for review. xprem's existing upload, asset hashes, signed manifest,
Expo download/cache and signed rollback protocol remain in use. The launch asset is
JSON, not Hermes bytecode. AndroidX's engine executes regenerated JavaScript inside
its separate process; no downloaded JS entry runs in ReactHost.

The runtime validates ABI, required capabilities, expression types and operation
bounds again before installing a program snapshot. Each invocation validates its
installed parameter contract before serializing portable values. Unknown operations,
unknown/wrong capabilities, incompatible inputs or execution failures retain native
fallback. An authenticated publisher bypassing the CLI can send arbitrary bytes,
but the native launch path still accepts only the bounded IR schema.

A changed unsupported Kotlin body refuses the entire publication while normal
`assembleDebug`/D8 and `assembleRelease`/R8 remain independent. Neither a handwritten
acceptance script nor a manually entered function ID is part of this workflow.


The ABI 2 path now passes [final Samsung acceptance](SECURITY_MODEL.md#final-accepted-phase-6-baseline-and-device-proof):
Kotlin-only business and combined UI/logic publications, online/offline restart,
signed invalid-artifact rejection and signed rollback on one accepted replacement
APK. The first failed candidate and its restoration remain documented. Restore the
baseline source after acceptance; keep retained APK/classes and private contract
configuration outside git. Phase 7 is not started.


## Phase 7 Cloud integration

The same local analyzer/export flow can authenticate to Dootah Cloud with an
environment-supplied scoped token. Only Portable IR and bounded revision metadata
leave the developer/CI machine. Cloud returns its release/operation IDs; enabling
rollout is explicit. See [Cloud API](CLOUD_API.md) for configuration and the
[physical Cloud proof](evidence/phase7-20260928/acceptance.json). The direct xprem
adapter remains operator tooling, not the customer Cloud credential contract.
