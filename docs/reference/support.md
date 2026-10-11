# Supported Kotlin and Compose changes

This matrix describes **0.1.0-alpha.2**, Runtime ABI **3**, Logic ABI **1**. Structural Compose UI, build-generated native adapters, nested components and Compose state with installed callbacks each passed same-APK OTA on a physical device in a separate Mihon test install (see [status](status.md) and the
[structural record](../v2/MINIMAL_STRUCTURAL_OTA.md)). Alpha.1 used Runtime ABI 2; moving to ABI 3 requires a new baseline APK and a new runtime version/environment (see [upgrading](platform.md)).
The installed APK must contain the hook and every required capability. An eligible
hook is necessary but not sufficient: the source analyzer is deliberately narrower
than bytecode instrumentation. Use `dootah analyze` against the exact retained release.

## Entrypoints and rendering

| Construct | Current behavior |
| --- | --- |
| Application-module ordinary `@Composable` functions returning Unit | Top-level and a conservative simple class-member subset can be published if their installed identity matches |
| Inputs | Up to 9 explicit String, Boolean or signed 32-bit Int inputs, including their nullable forms; no object instances cross into logic. Callback parameters are described under callbacks and state |
| Native default arguments | Calls using defaults execute the original body; explicitly supplied inputs can dispatch. Defaults/signatures are frozen by the retained source skeleton |
| Simple members | Analyzer accepts a top-level non-generic class without supertypes/annotations, not inner/data/interface forms; no access to instance state in portable logic |
| BasicText | Exactly `BasicText(text)` or `BasicText(text = ...)`, with an explicit unambiguous import or fully qualified name; one nonblank string result, at most 256 UTF-16 units |
| Native baseline body | Unsupported bodies remain native. BasicText replacement still loses richer styling; the bounded Box/Material3 Text path below preserves supported source properties |
| Material3 `Text` | String `text`, `modifier`, and member tokens `colorScheme.secondary` / `tertiary`, `typography.bodyMedium` / `labelSmall`; omitted color/style retain Material3 defaults |
| Structural UI | One root Box, Text, `if` / `if-else` or native component call; nested Box/Text children; `if` / `if-else` over Logic ABI 1 Boolean expressions; an empty branch renders nothing; empty Box and child addition/removal |
| App-owned components (native adapters) | A capitalised call written as a direct statement of an eligible top-level hook (not inside a lambda) gets a build-generated Kotlin adapter. Arguments that read hook inputs or state (a name or a string template) become typed String/Int/Boolean parameters; every other argument is frozen in the APK and omitted arguments keep Kotlin defaults. Content lambdas are named slots (for example `AlertDialog(title = { … }, text = { … }, confirmButton = { … })`) or one trailing lambda (`Column { … }`); their statements are capitalised calls, optionally under `if` / `if-else`, nested up to four levels. Children keep the slot's receiver (for example `RowScope.weight`); inline containers such as `Column` and `Row` are verified through kotlinc's inlining marker in the retained classes. An update may change the surrounding logic and data values, and show, hide, reorder or re-condition installed calls, but may not add a call shape, slot or callback form the installed source did not make. Positional lambda arguments, content lambdas with parameters or other statements (locals, loops), `this`, file-private names, nullable-input arguments, wildcard-imported callees and calls in other modules get no adapter |
| Callbacks and state (`compose.interaction.v1`) | Hook parameters of type `() -> Unit` or `(String) / (Int) / (Boolean) -> Unit` (non-null, not `suspend` or `@Composable`) are passed as the installed function objects and never enter logic. `var x by remember { mutableStateOf(<literal>) }` with a String, Int or Boolean literal (at most eight, declared before the single root call, with explicit `androidx.compose.runtime` imports) is ordinary Compose state; an update may change its initial literal. A callback argument of an installed call (`onClick = onDismissRequest`, `{ x = it }`, `{ onDelete(x); onDismissRequest() }`) becomes an action of ordered steps that only assign hook state or invoke hook callbacks with Logic ABI 1 values. Each event runs once, in order, against the state left by the earlier ones; events of a disposed call site are dropped; while a new evaluation is pending, the last valid UI stays on screen. A callback body with any other statement, or one that starts or stops reading its parameter, is a different installed shape and needs a new APK. Baselines built before this capability never receive state or callbacks || Layout | Box alignment tokens; ordered `fillMaxWidth()`, integer-Dp padding (all, horizontal/vertical, or start/top/end/bottom), and BoxScope `align` |
| Frozen Dp values | Unambiguous same-package retained top-level immutable values with integer literal `.dp` initializers; no getters, delegates, imports from other modules, or computed values |
| Other modifiers, styling, layouts, callbacks/events | Refused outside native adapters: the Box/Text path has no Row/Column or resources; state and callbacks only as above; no arbitrary native invocation (native adapters invoke only installed, build-generated call shapes and installed callbacks). BasicText still accepts only text |
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

Not supported in this alpha: object models (data classes, domain objects or any
non-scalar value as a hook input or in logic), collections, navigation changes
(destinations, routes, back-stack behavior), composables in library modules (only
application-module code is instrumented), adding new component calls, slots or
callbacks that the installed source did not make, and general Kotlin execution.

Loops, recursion, mutable state other than the bounded Compose state above, collections, exceptions, async/suspend, clock/random,
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
Structural limits: 64 nodes, depth 12, eight modifiers per node, sixteen slots including at most eight Strings; Dp integers 0–1024. Inactive branch slots must be null; only selected-branch expressions execute. All branches are validated. Interaction limits: eight state values, sixteen actions per entry, eight steps per action, callbacks with at most one argument, eight events per native call, content nesting four. See [security](../security/README.md).

The matrix is checked against [ComposeEntryVisitor](../../v2/android-sdk/gradle-plugin/src/main/groovy/dev/dootah/gradle/ComposeEntryVisitor.groovy),
[SourceAnalyzer](../../v2/publishing/src/main/groovy/dev/dootah/publish/SourceAnalyzer.groovy),
[LogicLowering](../../v2/publishing/src/main/groovy/dev/dootah/publish/LogicLowering.groovy),
[PortableProgram](../../v2/portable/src/main/java/dev/dootah/portable/PortableProgram.java),
[logic regressions](../../v2/publishing/src/test/groovy/dev/dootah/publish/LogicTest.groovy),
[publishing regressions](../../v2/publishing/src/test/groovy/dev/dootah/publish/PublishingTest.groovy),
[interaction regressions](../../v2/publishing/src/test/groovy/dev/dootah/publish/InteractionTest.groovy)
and [real Compose tests](../../v2/android-sdk/gradle-plugin/src/test/groovy/dev/dootah/gradle/ComposeEntryTest.groovy).
