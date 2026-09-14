package dev.dootah.contract

/**
 * The named values a bundle may refer to instead of carrying a constant.
 *
 * A token is resolved by the installed app, against its own theme. That is the
 * difference between a remotely described component that belongs to the app and
 * one that merely sits inside it: a themed colour follows dark mode, a palette
 * change, and whatever the host app configured, exactly as the native code it
 * replaced did.
 *
 * Each set is closed and resolved by an exhaustive `when` on the Android side,
 * so a bundle naming something not listed here is a missing requirement caught
 * when it is published -- never a lookup by name against the app's internals.
 */
public object ThemeColors {

    /** `MaterialTheme.colorScheme` members, by property name. */
    public val ALL: List<String> = listOf(
        "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
        "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
        "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
        "background", "onBackground",
        "surface", "onSurface", "surfaceVariant", "onSurfaceVariant", "surfaceTint",
        "inverseSurface", "inverseOnSurface",
        "error", "onError", "errorContainer", "onErrorContainer",
        "outline", "outlineVariant", "scrim",
        "surfaceBright", "surfaceDim",
        "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest",
        "surfaceContainerLow", "surfaceContainerLowest",
    )

    public fun isKnown(token: String): Boolean = token in ALL
}

public object Shapes {

    public const val CIRCLE: String = "circle"
    public const val RECTANGLE: String = "rectangle"

    public val ALL: List<String> = listOf(CIRCLE, RECTANGLE)

    public fun isKnown(token: String): Boolean = token in ALL
}

/**
 * The `Alignment` values a bundle may name on a layout.
 *
 * Three closed sets rather than one, because Compose's type system already
 * separates them and collapsing them here would let a bundle put
 * `CenterVertically` on a `Column`, which has no such axis. A `Column` takes an
 * `Alignment.Horizontal`, a `Row` an `Alignment.Vertical`, and a `Box` a
 * two-axis `Alignment`; the names are Compose's own property names, so there is
 * nothing to translate and nothing to keep in step but this list.
 *
 * Named, never serialised. An `Alignment` is an object with behaviour -- it
 * computes a position -- so carrying one would mean carrying code. The bundle
 * says which of these the app already has, and the app resolves it from an
 * exhaustive `when`.
 */
public object Alignments {

    /** `Column(horizontalAlignment = ...)`, an `Alignment.Horizontal`. */
    public val HORIZONTAL: List<String> = listOf("Start", "CenterHorizontally", "End")

    /** `Row(verticalAlignment = ...)`, an `Alignment.Vertical`. */
    public val VERTICAL: List<String> = listOf("Top", "CenterVertically", "Bottom")

    /** `Box(contentAlignment = ...)`, a two-axis `Alignment`. */
    public val BOX: List<String> = listOf(
        "TopStart", "TopCenter", "TopEnd",
        "CenterStart", "Center", "CenterEnd",
        "BottomStart", "BottomCenter", "BottomEnd",
    )

    public fun isKnownHorizontal(token: String): Boolean = token in HORIZONTAL

    public fun isKnownVertical(token: String): Boolean = token in VERTICAL

    public fun isKnownBox(token: String): Boolean = token in BOX
}

/**
 * The `Arrangement` values a bundle may name on a layout.
 *
 * Split by axis for the same reason as [Alignments]: `Arrangement.Top` is a
 * `Vertical` and means nothing on a `Row`. `Center`, `SpaceBetween`,
 * `SpaceAround` and `SpaceEvenly` are in both sets because Compose declares one
 * object implementing both interfaces.
 *
 * [SPACED_BY] is the one entry carrying a value rather than being a bare name --
 * see [LayoutArrangement].
 */
public object Arrangements {

    /** `Arrangement.spacedBy(16.dp)`, the only arrangement that takes a value. */
    public const val SPACED_BY: String = "spacedBy"

    /** `Column(verticalArrangement = ...)`, an `Arrangement.Vertical`. */
    public val VERTICAL: List<String> = listOf(
        "Top", "Bottom", "Center", "SpaceBetween", "SpaceAround", "SpaceEvenly", SPACED_BY,
    )

    /** `Row(horizontalArrangement = ...)`, an `Arrangement.Horizontal`. */
    public val HORIZONTAL: List<String> = listOf(
        "Start", "End", "Center", "SpaceBetween", "SpaceAround", "SpaceEvenly", SPACED_BY,
    )

    public fun isKnownVertical(token: String): Boolean = token in VERTICAL

    public fun isKnownHorizontal(token: String): Boolean = token in HORIZONTAL
}

/**
 * One arrangement, as a bundle carries it.
 *
 * A token and, for `spacedBy` alone, the gap in dp. Kept as one type with an
 * optional value rather than two, because both ends have to decide "which
 * arrangement" before they can ask "how much", and splitting them put that
 * decision in two places.
 *
 * [spacing] is meaningless for every other token and is not read for them; a
 * `spacedBy` that arrives without one is a malformed bundle, which the app's
 * parser refuses rather than defaulting to zero and drawing a layout nobody
 * described.
 *
 * It is a [Dimension] rather than a number because `Arrangement.spacedBy(
 * MaterialTheme.padding.small)` is the commonest shape this takes in a real
 * app, and the gap in it belongs to the app.
 *
 * Named `LayoutArrangement` rather than `Arrangement` on purpose: the renderer
 * that resolves it imports Compose's `Arrangement` in the same file, and one of
 * the two would have to be written out in full at every use.
 */
public data class LayoutArrangement(
    public val token: String,
    public val spacing: Dimension? = null,
)
