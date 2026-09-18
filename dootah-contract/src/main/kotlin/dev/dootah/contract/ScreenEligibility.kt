package dev.dootah.contract

/**
 * The structural facts about one Compose function that decide whether Dootah
 * may take it over.
 *
 * Deliberately a plain data carrier rather than a compiler type. The decision is
 * made twice against two different representations -- once by the frontend that
 * produces the bundle, once by the backend that builds the APK -- and those two
 * runs happen in different compilations, of different versions of the source,
 * sometimes months apart. Anything they disagree about becomes a screen the
 * bundle describes and the app never asks for, or worse, the reverse.
 *
 * So the rule is written once, here, and each pass is responsible only for
 * filling in these fields honestly.
 */
public data class ComposableShape(

    /** Fully qualified name, which is also the screen's identity. */
    val fqName: String,

    val isComposable: Boolean,

    /** A composable that produces a value is read, not placed. */
    val returnsUnit: Boolean,

    /** `expect`, `external` and abstract declarations have nothing to lower. */
    val hasBody: Boolean,

    /**
     * Declared inside another function's body.
     *
     * Excluded because the two passes cannot agree on one: the backend walks
     * declaration containers and never sees them, so a frontend that accepted
     * them would describe screens the APK has no interception for.
     */
    val isLocal: Boolean,

    val isInline: Boolean,

    val isSuspend: Boolean,

    /** `@Preview` and friends: tooling entry points, never shipped UI. */
    val isPreview: Boolean,

    /**
     * Takes a receiver -- `ColumnScope.()`, `BoxScope.()`.
     *
     * The receiver is supplied by whatever the function is called inside, and
     * a remote description has no way to reconstruct it.
     */
    val hasReceiver: Boolean,

    /**
     * Takes `@Composable` content as a parameter.
     *
     * A wrapper's content belongs to its caller, not to it, so there is nothing
     * here for a bundle to own. These are the `MyCard(content: …)` helpers every
     * app has; counting them as failures would be dishonest.
     */
    val takesComposableContent: Boolean,

    /** Marked `@DootahNative`, or inside something that is. */
    val isSuppressed: Boolean,
)

/** Why Dootah passed over a Compose function. */
public enum class IneligibleReason {
    NOT_COMPOSABLE,
    RETURNS_A_VALUE,
    NO_BODY,
    LOCAL_FUNCTION,
    INLINE,
    SUSPEND,
    PREVIEW,
    HAS_RECEIVER,
    TAKES_COMPOSABLE_CONTENT,
    EXCLUDED_BY_ANNOTATION,
    EXCLUDED_BY_FILTER,
}

/** The outcome of applying [ScreenEligibility] to one function. */
public sealed interface Eligibility {

    public object Eligible : Eligibility

    public data class Ineligible(val reason: IneligibleReason) : Eligibility
}

/**
 * Whether Dootah may take a Compose function over the air, without being asked.
 *
 * The rule is intentionally coarse and purely structural. It answers "could this
 * shape of function ever be a Dootah screen", never "will this body lower" --
 * the second question has a different answer in every version of the source, and
 * the installed APK is built from a version the bundle has never seen. A rule
 * that looked at bodies would let the two passes drift apart on an ordinary
 * edit, which is the one failure this whole design exists to prevent.
 *
 * A function that is eligible but whose body cannot be lowered simply is not in
 * the bundle. The app then finds no remote implementation for it and renders the
 * native one, which is the same path an app takes before its first update.
 */
public object ScreenEligibility {

    public fun of(shape: ComposableShape, filter: ScreenFilter): Eligibility {

        // Ordered so the reported reason is the one a developer can act on.
        // A suppressed function reports the developer's own instruction rather
        // than whatever else happens to be true about it.
        val reason = when {
            !shape.isComposable -> IneligibleReason.NOT_COMPOSABLE
            shape.isSuppressed -> IneligibleReason.EXCLUDED_BY_ANNOTATION
            !shape.returnsUnit -> IneligibleReason.RETURNS_A_VALUE
            !shape.hasBody -> IneligibleReason.NO_BODY
            shape.isLocal -> IneligibleReason.LOCAL_FUNCTION
            shape.isInline -> IneligibleReason.INLINE
            shape.isSuspend -> IneligibleReason.SUSPEND
            shape.isPreview -> IneligibleReason.PREVIEW
            shape.hasReceiver -> IneligibleReason.HAS_RECEIVER
            shape.takesComposableContent -> IneligibleReason.TAKES_COMPOSABLE_CONTENT

            !filter.accepts(shape.fqName) ->
                IneligibleReason.EXCLUDED_BY_FILTER

            else -> return Eligibility.Eligible
        }

        return Eligibility.Ineligible(reason)
    }

    /** Convenience for the passes, which only ever branch on the outcome. */
    public fun accepts(shape: ComposableShape, filter: ScreenFilter): Boolean =
        of(shape, filter) is Eligibility.Eligible
}
