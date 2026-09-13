package dev.dootah.contract

/**
 * Which of an app's Compose functions Dootah is allowed to consider.
 *
 * Project-level configuration, expressed as patterns over fully qualified
 * names, so that keeping a package native is one line in a build script rather
 * than an annotation on every function in it.
 *
 * Carried through the compiler as a string because it has to reach two separate
 * compiler invocations with identical meaning, and a string that both ends parse
 * with this same code cannot drift the way two option lists can.
 */
public class ScreenFilter private constructor(
    private val include: List<String>,
    private val exclude: List<String>,
) {

    /**
     * Whether [fqName] is in scope.
     *
     * No `include` means everything the compilation contains, which is the
     * default an app gets for adding the plugin and nothing else. `exclude`
     * always wins: it is the answer to "not this one", and an instruction to
     * leave something alone should never need to out-argue a wildcard.
     */
    public fun accepts(fqName: String): Boolean {

        if (exclude.any { pattern -> matches(pattern, fqName) }) return false
        if (include.isEmpty()) return true

        return include.any { pattern -> matches(pattern, fqName) }
    }

    /** The form this travels as, round-tripping through [parse]. */
    public fun encode(): String =
        (include.map { "+$it" } + exclude.map { "-$it" }).joinToString(",")

    public companion object {

        /** Everything in the compilation. */
        public val EVERYTHING: ScreenFilter = ScreenFilter(emptyList(), emptyList())

        public fun of(include: List<String>, exclude: List<String>): ScreenFilter =
            ScreenFilter(
                include = include.map { it.trim() }.filter { it.isNotEmpty() },
                exclude = exclude.map { it.trim() }.filter { it.isNotEmpty() },
            )

        public fun parse(encoded: String): ScreenFilter {

            val entries = encoded.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            return of(
                include = entries.filter { it.startsWith("+") }.map { it.substring(1) },
                exclude = entries.filter { it.startsWith("-") }.map { it.substring(1) },
            )
        }

        /**
         * Glob matching over a dotted name.
         *
         * `*` stops at a package separator and `**` crosses it, so
         * `com.example.ui.*` is one package and `com.example.**` is a tree.
         * A pattern naming a package with no wildcard matches that package and
         * everything under it, because writing `exclude("com.example.debug")`
         * and having it match nothing would be a trap.
         */
        internal fun matches(pattern: String, fqName: String): Boolean {

            if (!pattern.contains('*')) {
                return fqName == pattern || fqName.startsWith("$pattern.")
            }

            return Regex(regexFor(pattern)).matches(fqName)
        }

        private fun regexFor(pattern: String): String = buildString {

            var index = 0

            while (index < pattern.length) {
                when {
                    pattern.startsWith("**", index) -> {
                        append(".*")
                        index += 2
                    }

                    pattern[index] == '*' -> {
                        append("[^.]*")
                        index += 1
                    }

                    else -> {
                        append(Regex.escape(pattern[index].toString()))
                        index += 1
                    }
                }
            }
        }
    }
}
