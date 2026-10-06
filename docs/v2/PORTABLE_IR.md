> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.
> [Current supported source and IR bounds](../reference/support.md).

# Portable IR V1 — Phase 5

2026-09-28: **Phase 5 PASS for the bounded constant-text subset**, on the existing
Phase 4 Samsung SM-E426B APK. Kotlin source edits generated Portable IR, JS and
Hermes automatically, then passed signed OTA, native Compose rendering, isolation,
online/offline restart and signed rollback. No APK was installed during Phase 5.
See [publishing and device evidence](PUBLISHING.md).

## Installed capability boundary

The accepted Phase 4 entry ABI passes identity and Composer, **not Kotlin arguments**.
Its renderer calls foundation `BasicText(title, Modifier.dootahFrame(value))`.
Consequently this phase supports constant text, not parameter/state-dependent text,
layout trees, Material typography, callbacks or business logic. The fact that an
Android dependency contains another component does not make it available through
the installed dispatch contract.

Material 3 `Text` is deliberately refused: its theme/style defaults are not the
installed renderer's defaults. The accepted source edit explicitly uses ordinary
`androidx.compose.foundation.text.BasicText("Checkout via Kotlin OTA")`. This is a
normal Compose API call, not a Dootah annotation, wrapper or manual dispatch call.
No visual semantics are silently approximated to accept the sample.

## Schema

```json
{
  "schema": "dootah.portable",
  "abi": 1,
  "runtimeVersion": "installed-runtime",
  "apkSha256": "installed-apk-sha256",
  "overrides": [{
    "functionId": "dth1:<64 lowercase hex characters>",
    "requires": ["compose.basic-text.constant.v1"],
    "tree": {
      "type": "text",
      "value": {"type": "string", "value": "Checkout via Kotlin OTA"}
    }
  }]
}
```

This is Dootah data, independent of Expo, RN, JSX and JVM object models. V1 has one
installed capability and one leaf node. A publication contains 1–32 unique targets;
each text is nonblank and at most 256 UTF-16 units. Unknown fields, node/value kinds,
capabilities, IDs, duplicate targets, unpaired surrogate characters, ABI/runtime/APK
mismatches and oversized values are rejected. An analysis with no changes may have
an empty list; it creates no release.

Boolean and bounded nonnegative Int literals can be local analysis constants.
Only String values reach the text node. Constant `if/else` is evaluated during
analysis after **both branches** are checked. It is not remote conditional business
logic. IR and generated JS use deterministic ordering; identical source/contract
inputs produce identical IR and JS.

## Read-only analysis

`v2/publishing` is an independent JVM application. It uses pinned Kotlin 2.1.20
PSI parsing from `kotlin-compiler-embeddable`, with a standalone parser environment.
It never invokes Kotlin compilation, compiler plugins, FIR, IR, compiler mutation,
source writes or Compose transformation. Parser bootstrap APIs are a pinned tooling
dependency, not a compatibility bridge to the consumer compiler. Parser upgrades
need tests; this does not claim a permanently stable PSI bootstrap API.

PSI provides syntax; this tool does not pretend to resolve general Kotlin semantics.
It accepts a closed grammar, explicit known rendering symbols and proven installed
JVM forms. Unknown constructs are refused as whole functions. Project declarations
that shadow the accepted rendering/package/annotation symbols are refused. No
partial subtree extraction, lambda lifting or arbitrary Kotlin-to-JS exists.
The public [Analysis API description](https://kotlin.github.io/analysis-api/index_md.html)
distinguishes PSI syntax from semantic analysis; broader resolution can be assessed
separately when a later supported subset actually needs it.

| Construct | V1 behavior |
| --- | --- |
| Foundation `BasicText` with only its String text argument | Supported with explicit import or fully qualified call |
| String literals, escapes, raw literals without interpolation | Supported |
| Immutable untyped local literal constants and references | Supported within the function |
| Constant Boolean `if/else` | Both branches checked, chosen tree emitted |
| Primitive/String parameters, nullable String, String extension receiver | Identity recognized; body must not read them |
| Nonzero default argument masks | Installed Phase 4 guard runs the native body |
| Top-level, simple member, overload, private/internal top-level | Identity checked against installed method |
| Material `Text`, style/modifier arguments, other calls/layouts | Refused; no semantic approximation |
| Parameter interpolation, receiver reads, remember/state | Refused; values are not passed by the installed hook |
| Mutable locals, side effects, callbacks, loops, arbitrary expressions | Refused as a whole function |
| Generic/suspend/inline/override declarations, boxed primitives, object parameters | Native/refused |
| File JVM annotations, aliases, uncertain owner/mangling/applier forms | Native/refused |
| New file, signature, import, declaration/default or ordinary-function change | Requires a new installed contract |

Each file's source outside declared composable bodies is frozen against its retained
baseline snapshot. Unchanged unsupported functions remain native. If **any changed**
function is unsupported, the entire publication is refused, including otherwise
supported siblings. Every publication is a complete set of supported body changes
relative to the installed baseline, not an incremental patch over a previous OTA.

## One identity algorithm

The analyzer compiles the **same Java source**
`dev.dootah.identity.FunctionIdentity` used by the Phase 4 Gradle plugin. It does
not duplicate the hash algorithm. Bounded source forms supply owner, JVM name,
descriptor, static/member flag, extension flag and source nullability. Unknown
forms are not inferred. The resulting identity must agree with the retained
instrumented method descriptor and its actual inserted ID constant.

Import verifies retained class SHA-256 values from the accepted build record and
reads the final APK's ID strings. This catches shrinker elimination as well as
identity mismatches. The accepted baseline has ten source/bytecode agreements,
covering top-level, String/Int overloads, extension, member, nullable/default and
visibility forms. Nine of those IDs survive in the APK. `NullableDefault` agrees
before R8 but its only call supplies defaults, so R8 eliminates that ID; publication
to it is refused. The generic interface implementation has a retained APK ID but
the analyzer deliberately does not support its override declaration.

The installed contract is trusted release input, bound locally by its SHA-256 and
the retained APK SHA-256. Retained class hashes and baseline sources must come from
the same accepted build; an arbitrary source snapshot is not a build attestation.
This phase does not add cryptographic build provenance or fleet attestation. A
runtime/channel must represent one compatible installed capability contract; the
unchanged Expo server selects by runtime/channel, not by an added APK-hash header.

## IR to execution

Strict validation lowers the text leaf to the existing dispatch ABI:

```text
Kotlin body -> Portable IR V1 -> generated semantic dispatch JS
           -> locked Metro/Hermes export -> signed xprem release
           -> installed Phase 4 ID hook -> native BasicText -> frame health
```

Generated JS imports brownfield messaging and sends a validated
`dootah.dispatch.v1` envelope. It creates no React surface and renders no RN UI.
The publisher validates the generated tree before bundling, checks Hermes magic
and bytecode version against the APK bootstrap, and sends the export through the
existing server adapter. The runtime's existing strict dispatch parser, Expo
signature/hash checks, cache, launch selection and frame-health path are unchanged.

Portable IR itself is a publishing representation; the already-installed APK
consumes its validated Phase 4 lowering. No runtime IR interpreter or new native
capability is claimed. The final tool reproduced the accepted IR and JS exactly;
Hermes binary equality is not claimed because Expo embeds randomized temporary
source paths in debug information.

## Tests and limits

Nine JVM tests cover real retained identities, literal/constant/conditional lowering,
whole-function refusal, signature/body distinction, unknown IDs, ABI/stale-contract
checks, determinism and absence of Android task integration. Two Node tests exercise
deterministic generated JS in an isolated VM and reject malformed trees/values,
unknown or removed IDs, capability/ABI/runtime/APK mismatches and injected strings.
CLI refusal probes and the physical proof are documented in [PUBLISHING.md](PUBLISHING.md).

No general parameter serialization, state transfer, navigation, layout, Material
style fidelity or deterministic remote business logic is implemented. RN/Expo's
broad inherited native module exposure remains a known Phase 6 concern: a restricted
generator is not a security sandbox for arbitrary signed JS. The Phase 2C corrupted
HTTP-cache availability limitation remains. No V1 compiler matrix was rerun.

## Exact Phase 6 starting point

Define and test the installed capability boundary: inventory and constrain inherited
RN/Expo native modules and JS globals, specify deterministic portable logic and
capability references, and fail closed on capability/runtime disagreement. Preserve
the independent publisher, shared identity model, intact native fallback, signed
updates and real frame health. Broader rendering/parameter support requires an
explicit installed capability and a new APK when the current entry ABI is insufficient.
Phase 6 began on 2026-09-28 and stopped at its architectural security gate. A signed
probe on this same installed APK demonstrated direct RN/Expo access, networking,
filesystem operations and dynamic JS execution without a Dootah render envelope.
See [security investigation](SECURITY_MODEL.md), [capability status](CAPABILITIES.md)
and [portable logic status](PORTABLE_LOGIC.md). That initial investigation did not claim secure execution. The continuation below documents the replacement ABI and sandbox.

## Phase 6 ABI 2

The preceding ABI 1 specification is retained for existing evidence. Secure publication
now uses `schema: dootah.portable`, `runtimeAbi: 2`, `logicAbi: 1`, exact `runtimeVersion`,
root `requires: [logic.pure.v1, compose.basicText.v1]`, and 1–32 overrides. Each override
contains the automatically derived `functionId`, an ordered portable parameter-type
array, and one typed `expression` producing String. Nodes are `literal` (type/value),
`input` (index), or a closed operation with `args`. There is no arbitrary JS field,
module name, native object or generic capability-call opcode.

See [Portable Logic](PORTABLE_LOGIC.md) for operators, semantics and bounds and
[capabilities](CAPABILITIES.md) for the installed contract and typed dispatch ABI.
The shared Java compiler validates on both sides, generates reviewable publisher JS,
and regenerates executable source inside the APK. Expo transports signed IR data;
AndroidX JavaScriptSandbox executes the result in a separate process. This changes
execution/transport artifact ABI and therefore requires the Phase 6 baseline APK.
