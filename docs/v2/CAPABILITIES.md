> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# Installed capabilities — Phase 6 status

**Current status:** Phase 6 PASS. Installed runtime/dispatch ABI 2 and Portable Logic ABI 1 are validated by publisher and runtime on Samsung. See [final acceptance](evidence/phase6-20260928/acceptance.json). The historical failures below remain preserved.


## Historical investigation before the replacement runtime

2026-09-28: **No secure installed capability contract has been implemented.**
Phase 6 stopped at the [physical runtime isolation failure](SECURITY_MODEL.md).
This document distinguishes the current contract from the required continuation;
it is not a new capability schema or permission claim.

## Current implemented contract

Phase 5's local installed contract has `schema: 1`, `irAbi: 1`, `dispatchAbi: 1`
and `capabilities: ["compose.basic-text.constant.v1"]`, plus APK/source/identity
records and runtime configuration. Portable IR declares that capability in each
override's `requires`. The publisher validates exact agreement, installed IDs and
the retained APK/contract pins. This protects its supported lowering workflow.

| Capability | Input/output | Limits | Implementation / native requirements |
| --- | --- | --- | --- |
| `compose.basic-text.constant.v1` | Installed function ID and constant String; native Unit rendering | 1–32 overrides, nonblank text at most 256 UTF-16 units | SDK ComposeDispatch/BasicText and native frame health; installed Compose/UI support, no application permission |

Rendering is a UI effect, not pure calculation. It receives no Kotlin arguments.
The native parser validates the `dootah.dispatch.v1` envelope and ABI 1. **It does
not consume Portable IR `requires`, enforce a native capability registry, or authorize
all operations performed by the downloaded JavaScript.** No versioned pure-logic,
storage, navigation or typed-input capability has been installed.

## Required continuation, not implemented

Define a versioned installed schema only alongside the enforceable native execution
boundary. Every capability needs a stable ID, exact semantic version, portable input
and output types, limits, purity/effect classification, fixed native implementation
and required Android permissions/libraries. Bind the whole program to runtime ABI,
capability contract and installed function identities; validate the same requirements
at publication and again natively before evaluation/activation. No unknown fields,
unknown operation, version mismatch, extra argument or coerced native object may pass.

Newly supporting Kotlin parameters requires a new dispatch ABI and APK. The existing
Composer argument stays entirely in native rendering. Never serialize Context,
Activity, View, Composer, scopes, arbitrary class instances or JVM references.

Use only the minimum fixed rendering capability needed for business-logic acceptance.
Pure logic needs no generic native invocation. Storage/navigation remain absent
unless explicitly designed and installed. A name-based RN/Expo adapter, even behind
an apparent Dootah wrapper, is not the required capability boundary.

Publisher refusal and native fallback must cover undeclared/version-mismatched
capabilities, malformed typed values, incompatible inputs, stale runtime ABI and
resource exhaustion. A failure must reject the entire UI/logic release atomically.
Safe fallback must prevent effects, not merely ignore the final render message.
These are outstanding Phase 6 gates, not verified behaviors of the current APK.

## Phase 6 continuation: installed contract ABI 2

The preceding section records the insecure Phase 5 state. The replacement contract
is implemented in `v2/portable/src/main/java/dev/dootah/portable/PortableProgram.java`
and packaged as `assets/dootah-capabilities.json`. The publisher imports it from the
retained APK and requires exact equality with its supported contract. The runtime
has the same definitions compiled into the APK. The asset/code equality is tested.

- Runtime/dispatch ABI: **2**. Portable Logic ABI: **1**.
- `logic.pure.v1`: pure typed calculation, String/Boolean/Int and explicit nullable
  variants in and out, no Android permission. Shared validator/compiler plus
  AndroidX JavaScriptSandbox implement it.
- `compose.basicText.v1`: effectful native BasicText(String) -> Unit; at most 256
  UTF-16 units, nonblank result; installed Compose implementation, no device permission.
- Exact root `requires`: `["logic.pure.v1", "compose.basicText.v1"]`.
  Missing, extra, unknown or wrong-version entries reject the entire artifact.
- No storage, navigation, filesystem, network, device or generic native-invocation
  capability exists. No string-based bridge can look up a native module.

The new bytecode hook is `ComposeEntry.tryRenderV2`. Installed code supplies its
fixed input type declaration and boxes only Int/Boolean/String values into an
internal array. The SDK validates exact types, nullability, bounds and parameter
count before JSON serialization. Only JSON portable values enter the sandbox;
Composer and native object references stay in Android. Default-mask calls continue
through the original native implementation. ABI 1 is never repurposed as ABI 2.
Unknown function IDs cannot match an installed hook. Runtime input disagreement or
evaluation failure drops the override and returns to native behavior.

Native capability/ABI changes require a new APK/runtime version. The Phase 6 target
is `dootah-v2-logic-2` with SDK/plugin `0.6.0-local`; it does not reinterpret the
old `dootah-v2-hook-1` cache as portable logic.


## Validation proof

[Final Samsung acceptance](evidence/phase6-20260928/acceptance.json) demonstrates
signed missing/unknown/version-mismatched capabilities, an unknown native opcode,
incompatible runtime ABI and typed input, malformed values and budget overflow all
reject to native fallback. Host tests exercise the same shared validator in the
publisher. Fixed BasicText rendering is the sole side-effecting capability; there is
no generic name-based capability dispatcher. Direct raw-sandbox native access attempts
are separately proven blocked, independently of publisher restrictions.
