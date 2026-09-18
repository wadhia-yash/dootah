package dev.dootah.compiler.generate

/**
 * Renders a Kotlin string literal for [value].
 *
 * Escapes the dollar sign as well as the obvious characters: generated source is
 * compiled as Kotlin, where an unescaped `$` starts a template and would turn a
 * price label into either a compile error or a reference to something else
 * entirely.
 */
internal fun kotlinStringLiteral(value: String): String {

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
 */
internal fun sanitizeForIdentifier(screenId: String): String =
    screenId
        .map { character -> if (character.isLetterOrDigit()) character else '_' }
        .joinToString("")
