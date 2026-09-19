package dev.dootah.contract

/**
 * The identity of a piece of a screen that stays exactly as it was written.
 *
 * Most native components are *reusable*: the app registers an entry point and a
 * bundle supplies the arguments, so the same component can be placed anywhere,
 * any number of times, with different values each. That is [AdapterId], and it
 * is what makes ordinary updates possible.
 *
 * Some regions cannot work that way. A component that reads the scope of the
 * layout around it, a layout whose children include one, an argument no
 * vocabulary can express: for these the honest answer is that the region is
 * native code, and a bundle's only power over it is where it appears and
 * whether it appears at all.
 *
 * Such a region is therefore named by *what it is*, not by where it sits: the
 * composable being called plus a digest of the source the call was written as.
 * Nothing positional, so deleting a sibling renames nothing -- and two identical
 * regions in one screen are deliberately the same region, because they are the
 * same code.
 *
 * Naming a region by its own text has one consequence worth stating plainly:
 * editing the text of a frozen region changes its name, so a bundle published
 * from the edited source asks the installed app for a region it does not have,
 * and the screen falls back to native. That is correct. Native code cannot be
 * changed over the air, and a rename is how Dootah notices someone tried.
 */
public object FrozenRegionId {

    /**
     * Builds the id for one frozen region.
     *
     * [source] is the region's source text, exactly as it appears in the file
     * the pass is reading. It is normalised here rather than by the caller, so
     * the two passes -- one reading the source the APK was built from, the other
     * the source a bundle is published from -- cannot normalise it differently.
     */
    public fun of(qualifiedName: String, source: String): String =
        PREFIX + qualifiedName + "@" + digest(normalise(source))

    /** Whether an adapter id names a frozen region rather than a reusable one. */
    public fun isFrozen(adapterId: String): Boolean = adapterId.startsWith(PREFIX)

    /**
     * The name a kept region goes by when it is not a single call.
     *
     * An `if` that chooses between components has no callee to be named after,
     * and it still has to be nameable: a conditional whose condition the app
     * owns is one of the commonest reasons a region cannot be described, and
     * before this it was the reason a whole screen stayed native instead. The
     * text after it still tells two of them apart, which is the part that has
     * to be right.
     *
     * Not a qualified name, and deliberately unspellable as one, so it can
     * never collide with a region named after a real function.
     */
    public const val CONDITIONAL: String = "<conditional>"

    /**
     * Reduces source text to what it means rather than how it was typed.
     *
     * Two reductions, and only two.
     *
     * Whitespace, so that reformatting a region -- a line wrapped, an argument
     * moved onto its own line -- does not rename it: none of that changes the
     * code the app runs. A comment does rename it, which is a price worth
     * paying for a rule that cannot misread a string literal containing what
     * looks like a comment.
     *
     * And the callee's own name, up to the bracket that opens the call. The
     * qualified name is already the other half of the id, so nothing is lost --
     * and the two passes disagree about how much of it their spans cover. The
     * frontend reads `com.moriafly.salt.ui.TextButton(…)` and the backend reads
     * `TextButton(…)` for the same call, which named the same region twice and
     * left every bundle that used one asking the app for a region it had under
     * the other name. Starting at the bracket is what makes the two agree by
     * construction rather than by both happening to span the same characters.
     */
    internal fun normalise(source: String): String {

        val trimmed = source.trim()

        val opening = trimmed.indexOfFirst { character -> character == '(' || character == '{' }

        val body = if (opening > 0) trimmed.substring(opening) else trimmed

        return body.replace(WHITESPACE, " ")
    }

    /**
     * A short, stable digest.
     *
     * FNV-1a rather than a cryptographic hash: this identifies a region within
     * one screen of one app, it is never a security boundary -- an unrecognised
     * id is refused, not trusted -- and it must be computed identically by two
     * compiler passes with no shared runtime beyond this module.
     */
    private fun digest(text: String): String {

        var hash = OFFSET_BASIS

        for (character in text) {
            hash = hash xor character.code.toLong()
            hash = (hash * PRIME) and MASK
        }

        return hash.toString(RADIX).padStart(DIGITS, '0')
    }

    /**
     * Marks an id as frozen.
     *
     * A character that cannot appear in a Kotlin qualified name, so a frozen id
     * can never collide with a reusable one.
     */
    public const val PREFIX: String = "!"

    private val WHITESPACE = Regex("\\s+")

    private const val OFFSET_BASIS = -3750763034362895579L
    private const val PRIME = 0x100000001b3L
    private const val MASK = 0x7fffffffffffffffL
    private const val RADIX = 36
    private const val DIGITS = 13
}
