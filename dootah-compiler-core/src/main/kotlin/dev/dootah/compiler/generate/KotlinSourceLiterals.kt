package dev.dootah.compiler.generate

/**
 * Renders a Kotlin string literal for [value].
 *
 * Escapes the dollar sign as well as the obvious characters: generated source is
 * compiled as Kotlin, where an unescaped `$` starts a template and would turn a
 * price label into either a compile error or a reference to something else
 * entirely.
 */
fun kotlinStringLiteral(value: String): String {

    val escaped = StringBuilder(value.length + 2)
    escaped.append('"')

    for (character in value) {
        when (character) {
            '\\' -> escaped.append("\\\\")
            '"' -> escaped.append("\\\"")
            '$' -> escaped.append("\\$")
            '\n' -> escaped.append("\\n")
            '\r' -> escaped.append("\\r")
            '\t' -> escaped.append("\\t")
            else -> escaped.append(character)
        }
    }

    escaped.append('"')
    return escaped.toString()
}

/**
 * A file-name-safe form of a screen id.
 *
 * Fully qualified function names can contain punctuation, so they cannot be
 * used as generated identifiers or paths as they stand.
 *
 * And they can be long. A screen is identified by the declaration it is, which
 * includes the parameters declared with it, so a settings dialog taking a dozen
 * of them produces a name past the 255 bytes a file name may have -- and the
 * build failed inside the compiler, on a path, naming nothing a developer could
 * act on. A name over the limit keeps as much of itself as fits and ends in a
 * digest of the whole, so it stays readable and stays unique.
 */
fun sanitizeForIdentifier(screenId: String): String {

    val flattened = screenId
        .map { character -> if (character.isLetterOrDigit()) character else '_' }
        .joinToString("")

    if (flattened.length <= MAXIMUM_NAME) return flattened

    val digest = screenId.hashCode().toUInt().toString(RADIX).padStart(DIGEST_LENGTH, '0')

    return flattened.take(MAXIMUM_NAME - DIGEST_LENGTH - 1) + "_" + digest
}

/**
 * How long a generated name may be.
 *
 * Under the 255 bytes a file name may have on every filesystem Dootah builds
 * on, with room for the extensions the reports add to it.
 */
private const val MAXIMUM_NAME = 180

private const val DIGEST_LENGTH = 7

private const val RADIX = 36
