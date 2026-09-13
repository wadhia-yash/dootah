package dev.dootah.compiler.fir

/**
 * Why Dootah refused a construct, as a name that survives rewording.
 *
 * The prose beside it is written for whoever is reading one build's output; this
 * is for counting across a hundred apps. Grouped by *what Dootah refused* rather
 * than by what it happened to be looking at, so that `IconButton`, `Chip` and
 * some app's own `FancyCard` all land in one place instead of looking like three
 * separate architectural problems.
 */
internal enum class RejectionCode {

    NO_BODY,
    NO_COMPOSABLE_CONTENT,

    /** A local whose type Dootah cannot carry: a State, a list, a domain object. */
    UNSUPPORTED_LOCAL_TYPE,
    UNREADABLE_LOCAL_INITIALIZER,

    UNSUPPORTED_STATEMENT_IN_LAYOUT,
    UNREADABLE_LAYOUT_CALL,
    UNSUPPORTED_LAYOUT_ARGUMENT,
    LAYOUT_WITHOUT_CONTENT,

    /** A call in a layout that is not a composable Dootah can place. */
    UNSUPPORTED_CALL_IN_LAYOUT,

    /** A composable drawn into the scope of the layout around it. */
    COMPONENT_READS_SCOPE,

    UNSUPPORTED_STATEMENT_IN_HANDLER,
    UNSUPPORTED_ASSIGNMENT_TARGET,

    /** A handler reaching something other than the screen's own callbacks. */
    UNSUPPORTED_CALL_IN_HANDLER,

    UNSUPPORTED_EXPRESSION,
    UNSUPPORTED_LITERAL,

    /** A parameter of a type that cannot cross to the bundle. */
    UNSUPPORTED_PARAMETER_TYPE,

    UNKNOWN_REFERENCE,
    UNSUPPORTED_OPERATOR,
    BRANCH_WITHOUT_VALUE,
    UNSUPPORTED_CALL_IN_VALUE,

    UNSUPPORTED_FUNCTION_RETURN,
    UNSUPPORTED_FUNCTION_PARAMETER,
    UNSUPPORTED_FUNCTION_BODY,

    /** The frontend could not resolve the call at all. */
    UNRESOLVED_CALL,

    UNREADABLE_COMPONENT_ARGUMENTS,
    CONTENT_NOT_A_LAMBDA,

    /** A component argument Dootah cannot describe to the app. */
    UNSUPPORTED_COMPONENT_ARGUMENT,

    UNSUPPORTED_MODIFIER,
    UNREADABLE_MODIFIER,
    UNSUPPORTED_MODIFIER_ARGUMENT,
    UNSUPPORTED_COLOR,
}

/**
 * Something in a screen that Dootah cannot send over the air.
 *
 * Carries where it is and what to do about it. A diagnostic that only says
 * "unsupported" leaves a developer guessing between rewriting the screen and
 * exposing a capability through the native bridge, which are very different
 * pieces of work.
 */
internal data class UnsupportedConstruct(
    val functionName: String,
    val filePath: String,
    val sourceOffset: Int?,

    /** What was found, in the developer's terms. */
    val found: String,

    /** What to do instead. */
    val remedy: String,

    /** The stable name for this refusal, for counting. */
    val code: RejectionCode,

    /**
     * The concrete thing refused -- a type, a callee, a modifier.
     *
     * Separated from [code] because the two answer different questions. The code
     * says which rule refused; this says what would have to be supported. A
     * hundred screens blocked on `UNSUPPORTED_PARAMETER_TYPE` are one problem if
     * the detail is always `androidx.lifecycle.ViewModel` and a hundred problems
     * if it is always something different.
     */
    val detail: String? = null,
)
