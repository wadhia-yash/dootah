# Supported Kotlin and Compose changes

This matrix describes the working-tree Runtime ABI **3** extension. The bounded Box/Text structural OTA slice passed physical Mihon acceptance (see the
[dated record](../v2/MINIMAL_STRUCTURAL_OTA.md)); the published alpha.1 and its prior evidence use ABI 2. A new baseline APK and runtime/environment are required.
The installed APK must contain the hook and every required capability. An eligible
hook is necessary but not sufficient: the source analyzer is deliberately narrower
than bytecode instrumentation. Use `dootah analyze` against the exact retained release.

## Entrypoints and rendering

| Construct | Current behavior |
| --- | --- |
| Application-module ordinary `@Composable` functions returning Unit | Top-level and a conservative simple class-member subset can be published if their installed identity matches |
| Inputs | Up to 9 explicit String, Boolean or signed 32-bit Int inputs, including their nullable forms; no object instances cross into logic |
| Native default arguments | Calls using defaults execute the original body; explicitly supplied inputs can dispatch. Defaults/signatures are frozen by the retained source skeleton |
| Simple members | Analyzer accepts a top-level non-generic class without supertypes/annotations, not inner/data/interface forms; no access to instance state in portable logic |
| BasicText | Exactly `BasicText(text)` or `BasicText(text = ...)`, with an explicit unambiguous import or fully qualified name; one nonblank string result, at most 256 UTF-16 units |
| Native baseline body | Unsupported bodies remain native. BasicText replacement still loses richer styling; the bounded Box/Material3 Text path below preserves supported source properties |
| Material3 `Text` | String `text`, `modifier`, and member tokens `colorScheme.secondary` / `tertiary`, `typography.bodyMedium` / `labelSmall`; omitted color/style retain Material3 defaults |
| Structural UI | One root Box, Text, `if` / `if-else` or native component call; nested Box/Text children; `if` / `if-else` over Logic ABI 1 Boolean expressions; an empty branch renders nothing; empty Box and child addition/removal |
| App-owned components (native adapters) | Not yet accepted on a device. A capitalised call written as a direct statement of an eligible scalar top-level hook (not inside a lambda) gets a build-generated Kotlin adapter. Arguments that read hook inputs (an input name or a string template) become typed String/Int/Boolean parameters; every other argument is frozen in the APK and omitted arguments keep Kotlin defaults. An update may change the surrounding logic and those values, or remove the call, but may not add a call shape the installed source did not make. A container call with one plain content lambda (for example `Row { … }` or `Column { … }`) whose statements are only capitalised calls, optionally under `if` / `if-else`, gets a container adapter: the update may show, hide, reorder or re-condition those installed child calls and change their data values; children keep the content lambda's receiver (for example `RowScope.weight`). One level only: children cannot have their own content. Content lambdas with parameters or other statements (locals, loops), callbacks, `this`, file-private names, nullable-input arguments, wildcard-imported callees and calls in other modules get no adapter |
| Layout | Box alignment tokens; ordered `fillMaxWidth()`, integer-Dp padding (all, horizontal/vertical, or start/top/end/bottom), and BoxScope `align` |
| Frozen Dp values | Unambiguous same-package retained top-level immutable values with integer literal `.dp` initializers; no getters, delegates, imports from other modules, or computed values |
| Other modifiers, styling, layouts, callbacks/events | Refused; no Row/Column, resources, state, callback lambdas, or arbitrary native invocation (native adapters invoke only installed, build-generated call shapes). BasicText still accepts only text |
| Library-module composables | Not instrumented; application-module call sites do not make library bodies remotely replaceable |
| Extension receivers | Some forms have native hooks, but Logic ABI 1 lowering refuses extension inputs |
| Generics, inline/suspend/override functions, extra annotations | Source publication refused; instrumentation eligibility alone does not promise publishability |
| Unknown Kotlin metadata | Instrumentation fails closed to native and warns; do not assume a successful Android build created OTA hooks |

## Portable Logic ABI 1

| Construct | Supported semantics |
| --- | --- |
| Types | String, Boolean, Int and explicit nullable variants; no Float, Double, Long, enum, tagged object or arbitrary data class |
| Literals | Decimal Int (including negative values through unary minus), Boolean, escaped/ordinary strings; null needs a known nullable type |
| Locals | Immutable `val`, optionally explicitly typed; bounded blocks of 1–16 statements; local declarations followed by a final result |
| Arithmetic | Int `+ - * / %`, unary `-`; 32-bit wrapping, division truncates toward zero, remainder follows dividend sign; division/remainder by zero fails the invocation |
| Comparison | Int `< <= > >=`; `== !=` on matching scalar base types with explicit nullability |
| Boolean | `!`, `&&`, `||`, preserving short circuit evaluation |
| Branching | `if/else` with matching result types; expression `when` with 2–8 branches, one value/Boolean condition each and a final `else` |
| Strings | Templates and String-left `+`; scalar/null conversion only. No arbitrary string method calls such as `uppercase()` or `toString()` |
| Helpers | Acyclic functions declared inside the edited body, explicit parameter/return types, positional arguments, no defaults/varargs; at most 8 active expansions |
| Evaluation | Initializers and helper arguments keep evaluation order, even unused failing expressions; all branches are validated, including unreachable ones |

There is no general exported pure-function API: the current source path composes
logic inside an eligible UI body. The structural path keeps its tree native and returns typed scalar text/condition slots; it does not return host objects or commands.
Nullable values can be compared/interpolated; there is no general Kotlin smart-cast,
safe-call, Elvis or non-null assertion support.

## Changes that stay native or need a new APK

Loops, recursion, mutable state, collections, exceptions, async/suspend, clock/random,
reflection, file/network calls, arbitrary imports, raw strings, unknown symbols and
external/top-level helper calls are outside portable lowering. Import aliases,
ambiguous wildcard imports (except `androidx.compose.runtime.*`), file/JVM facade
annotations, escaped/mangled names, nested/object/companion forms and unsupported
parameter declarations also refuse source publication.

Imports, declarations/signatures, call sites and top-level helper bodies outside the
retained composable bodies are frozen. Adding/removing Kotlin files or changing that
source skeleton refuses analysis. Put the necessary imports and baseline function in
the app **before** the native build; do not add them as part of the OTA edit.

Unchanged unsupported code stays native. A changed unsupported function refuses the
**whole publication**, even if another edited function is supported. Native builds
can still be valid; no partial update silently drops a refused change. Invalid runtime
inputs/programs/results or unavailable sandbox execution take native fallback.

New Android permissions, manifest entries, native SDKs/libraries, resources or native
capabilities absent from the installed APK require a new APK. That is an expected
boundary, not an OTA failure. Arbitrary Kotlin OTA is not supported.

## Enforced bounds and source evidence

Artifacts: 65,536 UTF-8 bytes; JSON nesting 64; at most 32 overrides, 9 inputs each;
512 expanded IR nodes per entry (all branches counted), expression depth 24; strings
256 UTF-16 units with valid surrogate pairs. Source lowering additionally limits
nesting to 20. Sandbox source 64 KiB, result 16 KiB, isolate heap 8 MiB, evaluation
wait 1 second then isolate closure. These are not a total app-memory or UI-latency SLA.
Structural limits: 64 nodes, depth 12, eight modifiers per node, sixteen slots including at most eight Strings; Dp integers 0–1024. Inactive branch slots must be null; only selected-branch expressions execute. All branches are validated. See [security](../security/README.md).

The matrix is checked against [ComposeEntryVisitor](../../v2/android-sdk/gradle-plugin/src/main/groovy/dev/dootah/gradle/ComposeEntryVisitor.groovy),
[SourceAnalyzer](../../v2/publishing/src/main/groovy/dev/dootah/publish/SourceAnalyzer.groovy),
[LogicLowering](../../v2/publishing/src/main/groovy/dev/dootah/publish/LogicLowering.groovy),
[PortableProgram](../../v2/portable/src/main/java/dev/dootah/portable/PortableProgram.java),
[logic regressions](../../v2/publishing/src/test/groovy/dev/dootah/publish/LogicTest.groovy),
[publishing regressions](../../v2/publishing/src/test/groovy/dev/dootah/publish/PublishingTest.groovy)
and [real Compose tests](../../v2/android-sdk/gradle-plugin/src/test/groovy/dev/dootah/gradle/ComposeEntryTest.groovy).
