package json

/**
 * Escapes a Kotlin string so that it is valid inside a JSON string literal.
 *
 * Both the UI serializer and the native bridge hand-build JSON text, and both
 * must escape identically: one unescaped control character produces a payload
 * the Android side cannot parse, which surfaces as an unexplained bundle failure
 * rather than an obvious encoding error.
 *
 * RFC 8259 forbids raw characters below U+0020 in a string literal. The two
 * previous copies of this function escaped only backslash, quote and newline,
 * so an ordinary tab or carriage return in bundle text emitted invalid JSON.
 */
internal fun String.escapeJson(): String {
    val escaped = StringBuilder(length)

    for (character in this) {
        when {
            character == '\\' -> escaped.append("\\\\")
            character == '"' -> escaped.append("\\\"")
            character == '\n' -> escaped.append("\\n")
            character == '\r' -> escaped.append("\\r")
            character == '\t' -> escaped.append("\\t")
            character == '\b' -> escaped.append("\\b")

            // Everything else below U+0020, form feed included, has no shorthand
            // escape and must be emitted in \uXXXX form.
            character.code < 0x20 -> escaped.appendUnicodeEscape(character)

            // Legal JSON, but these terminate a line in JavaScript source and
            // the payload crosses a JS boundary on its way to Android.
            character.code == 0x2028 -> escaped.append("\\u2028")
            character.code == 0x2029 -> escaped.append("\\u2029")

            else -> escaped.append(character)
        }
    }

    return escaped.toString()
}

private fun StringBuilder.appendUnicodeEscape(character: Char) {
    append("\\u")
    append(character.code.toString(16).padStart(4, '0'))
}
