package dev.dootah.contract

/**
 * Names a value the installed app computes, so a bundle can point at one.
 *
 * Most of what a screen cannot bundle is not complicated -- it is a number the
 * app already knows. `Arrangement.spacedBy(MaterialTheme.padding.small)` is one
 * call away from being describable, and the only thing in the way is that
 * `MaterialTheme.padding.small` is the app's own spacing scale rather than a
 * literal. Refusing the screen over it loses the whole layout; carrying `8.dp`
 * instead silently forks the app's spacing the first time someone retunes it.
 *
 * So the bundle carries neither. It names the value, and the APK supplies it --
 * the same trade already made for a themed colour, a string resource and a
 * screen's own parameters. An anchored value stays the app's: change the
 * spacing scale in a later release and every bundle that named it follows,
 * because the number never left the APK.
 *
 * ## What may be anchored
 *
 * A chain of property reads rooted at a named object or companion, as written:
 * `androidx.compose.material3.MaterialTheme.padding.small`. Nothing else --
 * no calls, no arguments, no locals, no arithmetic. A chain of plain reads is a
 * thing both compilations can name identically from the resolved symbols alone,
 * which is the whole requirement: the APK and the bundle are built from two
 * versions of the source, and a name that shifted between them would resolve to
 * the wrong number rather than to nothing.
 *
 * ## Why the root is fully qualified
 *
 * `Padding.small` alone would be ambiguous the moment two classes had a `small`.
 * The key is the chain as written, rooted at the qualifier's fully qualified
 * name, so it says which `small` without saying where in the file it was read.
 *
 * ## What a bundle can and cannot do with one
 *
 * It can name any anchor the installed screen registered, in any dimension,
 * any number of times, and it can stop naming one. It cannot invent a name: an
 * anchor exists only because the app's own source read that property in that
 * screen, exactly as a capability exists only for a method written literally in
 * the source. A bundle naming one the APK has not got is a missing requirement,
 * reported when the bundle is published.
 */
public object AnchorId {

    /**
     * The anchor for a property chain, given its root and the names read off it.
     *
     * [root] is a fully qualified class or object name; [path] is each property
     * short name in source order. Joined with dots because that is how it was
     * written and how it will be read in a build failure -- a key a developer
     * cannot recognise is a key they cannot act on.
     */
    public fun of(root: String, path: List<String>): String =
        (listOf(root) + path).joinToString(".")

    /**
     * Whether this is shaped like an anchor at all.
     *
     * Cheap, and it is not a security check -- the table lookup is. It exists so
     * that a malformed name is reported as malformed rather than as missing,
     * which are different things to debug.
     */
    public fun isWellFormed(id: String): Boolean =
        id.isNotEmpty() &&
            '.' in id &&
            id.split('.').all { part -> part.isNotEmpty() }
}

/**
 * A length in a bundle: either a number it decided, or a name the app resolves.
 *
 * One type rather than two fields, because every dimension is exactly one of
 * these and a shape that allowed both or neither would have to be checked
 * everywhere it was read.
 *
 * The literal case is what a bundle has always carried -- `16.dp` written in
 * the source, travelling as `16.0`. The anchored case is the app's own number,
 * named. A bundle may move freely between them: replacing a literal with an
 * anchor, or an anchor with a literal, is an ordinary edit and needs no new
 * APK, because both sides of the choice were already reachable.
 */
public data class Dimension(
    public val value: Double? = null,
    public val anchor: String? = null,
) {

    /** True when this names the app's value rather than carrying its own. */
    public val isAnchored: Boolean get() = anchor != null

    public companion object {

        public val ZERO: Dimension = Dimension(value = 0.0)

        public fun of(value: Double): Dimension = Dimension(value = value)

        public fun anchored(anchor: String): Dimension = Dimension(anchor = anchor)
    }
}
