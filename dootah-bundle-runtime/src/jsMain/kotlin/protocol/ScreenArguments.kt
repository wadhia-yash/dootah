package protocol

/**
 * The values a native caller passed into an intercepted Compose screen.
 *
 * Parsed from JSON the Android side builds out of the screen's actual
 * parameters. Only the types Dootah can carry appear here; a parameter of any
 * other type is rejected when the screen is lowered, so nothing has to be
 * guessed at run time.
 *
 * Every accessor fails loudly on a missing or wrongly typed value rather than
 * substituting a default. A disagreement between the generated code and the
 * arguments it is handed is a protocol bug, and a screen quietly rendering
 * `0` or `""` would hide it; failing surfaces it as a fallback to native.
 */
class ScreenArguments internal constructor(private val raw: dynamic) {

    fun string(name: String): String = read(name, "String") as? String ?: missing(name, "String")

    fun int(name: String): Int = when (val value = read(name, "Int")) {
        is Int -> value
        is Double -> value.toInt()
        else -> missing(name, "Int")
    }

    fun boolean(name: String): Boolean =
        read(name, "Boolean") as? Boolean ?: missing(name, "Boolean")

    /**
     * Read back from text.
     *
     * Every number here is a JavaScript double, so a Long past 2^53 arrives with
     * its low digits already gone. Sending it as text is the only way it arrives
     * intact, and this is the other half of that.
     */
    fun long(name: String): Long = when (val value = read(name, "Long")) {
        is String -> value.toLongOrNull() ?: missing(name, "Long")
        is Int -> value.toLong()
        is Double -> value.toLong()
        else -> missing(name, "Long")
    }

    fun float(name: String): Float = when (val value = read(name, "Float")) {
        is Int -> value.toFloat()
        is Double -> value.toFloat()
        else -> missing(name, "Float")
    }

    fun double(name: String): Double = when (val value = read(name, "Double")) {
        is Int -> value.toDouble()
        is Double -> value
        else -> missing(name, "Double")
    }

    fun stringOrNull(name: String): String? = readNullable(name)?.let { value ->
        value as? String ?: missing(name, "String?")
    }

    fun intOrNull(name: String): Int? = readNullable(name)?.let { value ->
        when (value) {
            is Int -> value
            is Double -> value.toInt()
            else -> missing(name, "Int?")
        }
    }

    fun booleanOrNull(name: String): Boolean? = readNullable(name)?.let { value ->
        value as? Boolean ?: missing(name, "Boolean?")
    }

    fun longOrNull(name: String): Long? = readNullable(name)?.let { value ->
        when (value) {
            is String -> value.toLongOrNull() ?: missing(name, "Long?")
            is Int -> value.toLong()
            is Double -> value.toLong()
            else -> missing(name, "Long?")
        }
    }

    fun floatOrNull(name: String): Float? = readNullable(name)?.let { value ->
        when (value) {
            is Int -> value.toFloat()
            is Double -> value.toFloat()
            else -> missing(name, "Float?")
        }
    }

    fun doubleOrNull(name: String): Double? = readNullable(name)?.let { value ->
        when (value) {
            is Int -> value.toDouble()
            is Double -> value
            else -> missing(name, "Double?")
        }
    }

    private fun read(name: String, type: String): Any? =
        readNullable(name) ?: missing(name, type)

    private fun readNullable(name: String): Any? {
        val value = raw[name]
        return if (value == null || value == undefined) null else value
    }

    private fun missing(name: String, type: String): Nothing =
        throw IllegalStateException("Dootah screen argument '$name' is not a $type")

    companion object {
        internal val EMPTY = ScreenArguments(js("({})"))
    }
}

/**
 * Reads the arguments envelope.
 *
 * `JSON.parse` rather than a Kotlin JSON library: the host runtime is a
 * JavaScript engine that already has a parser, and pulling in a second one would
 * add more to the download than the whole rest of a bundle.
 */
fun parseArguments(json: String): ScreenArguments {

    if (json.isEmpty()) return ScreenArguments.EMPTY

    return ScreenArguments(JSON.parse<dynamic>(json))
}
