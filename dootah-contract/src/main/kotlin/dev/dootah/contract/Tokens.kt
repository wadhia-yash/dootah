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
