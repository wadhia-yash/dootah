> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.
> [Current supported logic and bounds](../reference/support.md).

# Portable Logic — Phase 6 status

**Current status:** Phase 6 PASS. Kotlin-only business logic and combined UI/logic OTA are proven on Samsung with the bounded isolated runtime. See [final acceptance](evidence/phase6-20260928/acceptance.json). The historical failures below remain preserved.


## Historical investigation before the replacement runtime

2026-09-28: **Portable Logic V1 is not implemented or OTA-proven.**
The [security gate failed on the existing physical APK](SECURITY_MODEL.md).
No analyzer expansion, new runtime interpreter, capability ABI, typed dispatch,
business-logic baseline or performance claim was added after that finding.

The implemented [Portable IR V1](PORTABLE_IR.md) still handles constant BasicText.
Boolean and bounded nonnegative Int literals can participate in publishing-time
constant analysis; only String reaches the native render payload. Existing parameter
identities are recognized, but their values are not passed into remote execution.
There is no remote pure-function composition, arithmetic rule change, parameter
serialization, enum/tagged value handling or state capability.

## Outstanding grammar and bounds

After enforcing the execution boundary, specify exact Kotlin-compatible semantics
for String, Boolean, bounded Int, explicit nullability and closed enum/tagged values;
immutable locals; arithmetic/comparison/boolean operators; conditional/when branches;
acyclic pure function composition; deterministic string construction and validation.
Unknown calls and unsupported source must continue to refuse the entire publication.
Room, Retrofit instances, Context, suspend work, reflection, filesystem and hardware
APIs do not become portable merely because they appear in Kotlin source.

Set and test numeric range/overflow/division behavior, string encoding/size, program
bytes, expression/call depth and operation count. Recursion, loops, collections,
uncontrolled exceptions, async, clock, randomness, imports and dynamic code should
remain unsupported until precise bounded semantics and native enforcement exist.
Native validation must run before activation; a compiler-inserted JavaScript counter
alone can be omitted by a malicious signed bundle and is not a security budget.

Current limits of 32 render entries and 256 UTF-16 units per text constrain a render
message only. The publisher's 64 KiB generated-JS bound constrains that generator's
output only. The actual host has **no Dootah operation, recursion, loop, allocation
or execution-time limit** for arbitrary signed JS. Expo's health/recovery window is
not execution preemption. The probe demonstrated timer/time/random access; it did
not run an infinite loop or memory-exhaustion workload on the user's phone.

## Unperformed acceptance gates

Kotlin business-rule OTA, combined UI/logic atomic activation and rollback, typed
input/version/budget rejection, new-baseline online/offline cached restart and
portable/bridge/memory measurements remain unperformed. Normal Android builds and
compiler independence retain Phase 5's documented evidence; no build/runtime/compiler
implementation changed here and expensive completed-phase matrices were not repeated.
The handwritten security probe is not a substitute for Kotlin-only OTA acceptance.

Resume Phase 6 at the execution-boundary decision, then complete these gates on one
accepted new baseline with no reinstall/data clear during its acceptance sequence.
There is no Phase 7 starting point until Phase 6 passes in full.

## Phase 6 continuation: Portable Logic V1 implementation

The opening section records the original investigation stop. The new implementation
uses the separate sandbox described in [SECURITY_MODEL.md](SECURITY_MODEL.md).

Read-only Kotlin PSI recognizes explicit String, Boolean and signed 32-bit Int;
nullable input types; immutable local values; arithmetic/comparison/Boolean operators;
if/else; exhaustive `when` with 2–8 branches and one condition each; string templates;
and acyclic local pure helper functions with explicit parameter/return types.
Helpers are inlined conservatively. Top-level helper-body edits, external/instance
calls, unknown symbols, mutable locals, extension receivers in logic, raw strings,
collections, loops, recursion, suspend work, enums and arbitrary data classes remain
native/refused. No FIR/IR mutation or compiler plugin is used.

Local initializers and helper arguments retain their evaluation order, including
unused values that can fail. Boolean operators and conditional branches retain
short-circuit behavior. Int operations wrap in two's-complement 32 bits; multiplication
uses `Math.imul`, division truncates toward zero, remainder follows dividend sign,
and division/remainder by zero rejects the invocation. No floating point or implicit
numeric coercion crosses the input boundary. String interpolation converts only
portable scalar values, including nullable values (`null` -> `"null"`).

| Bound | Enforcement |
| --- | --- |
| Signed artifact | 65,536 UTF-8 bytes before JSON parsing |
| JSON nesting | 64 before parser recursion |
| Expression nesting | 24 natively; source lowering additionally bounded |
| Operations | 512 expanded IR nodes per entry; all branches included conservatively |
| Entries / parameters | 32 entries, 9 portable parameters per entry |
| Strings | 256 UTF-16 units; reject unpaired surrogates |
| Source blocks / when | 1–16 statements; 2–8 when branches |
| Helpers | Acyclic local helpers; maximum 8 active expansion calls |
| Sandbox source / result | 64 KiB source; 16 KiB result |
| Sandbox heap / timeout | 8 MiB isolate heap; 1 second evaluation deadline, then close/terminate |
| Cached invocations / pending work | 128 distinct invocations per process; at most 32 pending |
| Loops, recursion, collections, async | Unsupported |

`seq` in IR preserves initializer/argument evaluation; it is not an arbitrary
statement or loop facility. The APK regenerates executable JS from validated IR,
so a compromised publisher cannot omit budget enforcement or supply a loop instead.
Static expanded-node limits prevent exponential helper/local expansion before large
publisher serialization. The process sandbox independently contains adversarial raw
JS, including programs outside the accepted grammar.

A fresh isolate per evaluation prevents state/prototype contamination across calls.
The installed program snapshot selects UI and logic together; the acceptance example
renders the label and calculated amount as one native BasicText result. Until a typed
result is available, the hook executes its original native implementation. No native
frame is acknowledged merely because an artifact downloaded or a calculation ran.


## Source-driven acceptance and rollback

The retained baseline source uses ordinary Kotlin:

```kotlin
@Composable
fun CheckoutScreen(price: Int) {
    fun discount(p: Int): Int = p * 10 / 100
    val amount = discount(price)
    androidx.compose.foundation.text.BasicText("Discount: $amount")
}
```

The installed caller supplies `100`. Editing only `10` to `20`, then running Dootah
publish, displays `Discount: 20` on the same APK. Editing the label to
`Special discount` in that same source body publishes one atomic UI/business result.
Both restart modes and native frame health pass; signed rollback restores the
original label and 10% calculation. No manual JS, function IDs, Android rebuild or APK
install is part of those publications. The repository source is restored to the
10% baseline. [Physical acceptance and limits](SECURITY_MODEL.md#final-accepted-phase-6-baseline-and-device-proof)
include the corrected Compose-group regression, adversarial cases and measurements.
