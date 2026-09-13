package dev.dootah.contract

/**
 * Just enough JSON to write and read a contract.
 *
 * Hand-written because this module carries no third-party dependency: it is
 * loaded into the Kotlin compiler's own classloader at one end and shipped into
 * host apps at the other, and a serialisation library is a hazard in the first
 * of those.
 *
 * The subset is objects, arrays, strings and whole numbers -- which is the whole
 * of what a contract is. Anything else in the input is an error rather than
 * something to guess at: this file decides whether an update ships.
 */
internal sealed interface Json {

    data class Obj(val fields: Map<String, Json>) : Json

    data class Arr(val items: List<Json>) : Json

    data class Str(val value: String) : Json

    data class Num(val value: Int) : Json
}

internal class JsonException(message: String) : IllegalArgumentException(message)

/**
 * Renders canonically: fields in the order given, two-space indent, LF endings.
 *
 * Stable output matters because a contract is committed and diffed. The same
 * source has to produce the same bytes, or every build shows up as a change.
 */
internal fun Json.render(indent: String = ""): String = when (this) {

    is Json.Str -> "\"" + value.escaped() + "\""

    is Json.Num -> value.toString()

    is Json.Arr ->
        if (items.isEmpty()) "[]"
        else items.joinToString(
            separator = ",\n$indent  ",
            prefix = "[\n$indent  ",
            postfix = "\n$indent]",
        ) { item -> item.render("$indent  ") }

    is Json.Obj ->
        if (fields.isEmpty()) "{}"
        else fields.entries.joinToString(
            separator = ",\n$indent  ",
            prefix = "{\n$indent  ",
            postfix = "\n$indent}",
        ) { (name, value) ->
            "\"" + name.escaped() + "\": " + value.render("$indent  ")
        }
}

private fun String.escaped(): String = buildString {
    for (character in this@escaped) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else ->
                if (character < ' ') append("\\u" + character.code.toString(16).padStart(4, '0'))
                else append(character)
        }
    }
}

internal fun parseJson(text: String): Json {

    val reader = JsonReader(text)
    val value = reader.readValue()

    reader.skipWhitespace()
    if (!reader.atEnd()) throw JsonException("unexpected trailing content")

    return value
}

private class JsonReader(private val text: String) {

    private var at = 0

    fun atEnd(): Boolean = at >= text.length

    fun skipWhitespace() {
        while (at < text.length && text[at].isWhitespace()) at++
    }

    fun readValue(): Json = when (peek()) {
        '{' -> readObject()
        '[' -> readArray()
        '"' -> Json.Str(readString())
        else -> readNumber()
    }

    private fun readObject(): Json {

        expect('{')

        val fields = LinkedHashMap<String, Json>()

        if (peek() == '}') {
            at++
            return Json.Obj(fields)
        }

        while (true) {

            val name = readString()
            expect(':')
            fields[name] = readValue()

            when (val next = peek()) {
                ',' -> at++
                '}' -> {
                    at++
                    return Json.Obj(fields)
                }
                else -> fail("expected ',' or '}' but found '$next'")
            }
        }
    }

    private fun readArray(): Json {

        expect('[')

        val items = mutableListOf<Json>()

        if (peek() == ']') {
            at++
            return Json.Arr(items)
        }

        while (true) {

            items += readValue()

            when (val next = peek()) {
                ',' -> at++
                ']' -> {
                    at++
                    return Json.Arr(items)
                }
                else -> fail("expected ',' or ']' but found '$next'")
            }
        }
    }

    private fun readString(): String {

        expect('"')

        val built = StringBuilder()

        while (true) {

            if (at >= text.length) fail("unterminated string")

            when (val character = text[at++]) {

                '"' -> return built.toString()

                '\\' -> {

                    if (at >= text.length) fail("unterminated escape")

                    when (val escape = text[at++]) {
                        '"' -> built.append('"')
                        '\\' -> built.append('\\')
                        '/' -> built.append('/')
                        'n' -> built.append('\n')
                        'r' -> built.append('\r')
                        't' -> built.append('\t')
                        'b' -> built.append('\b')
                        'f' -> built.append('\u000C')
                        'u' -> {
                            if (at + 4 > text.length) fail("truncated unicode escape")
                            built.append(text.substring(at, at + 4).toInt(16).toChar())
                            at += 4
                        }
                        else -> fail("unknown escape '$escape'")
                    }
                }

                else -> built.append(character)
            }
        }
    }

    private fun readNumber(): Json {

        skipWhitespace()

        val start = at
        if (at < text.length && text[at] == '-') at++
        while (at < text.length && text[at].isDigit()) at++

        if (at == start) fail("expected a value")

        val digits = text.substring(start, at)

        return Json.Num(digits.toIntOrNull() ?: fail("'$digits' is not a whole number"))
    }

    private fun peek(): Char {
        skipWhitespace()
        if (at >= text.length) fail("unexpected end of input")
        return text[at]
    }

    private fun expect(character: Char) {
        skipWhitespace()
        if (at >= text.length || text[at] != character) fail("expected '$character'")
        at++
    }

    private fun fail(message: String): Nothing = throw JsonException("$message at offset $at")
}
