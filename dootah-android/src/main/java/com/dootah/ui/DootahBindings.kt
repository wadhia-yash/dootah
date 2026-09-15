package com.dootah.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import android.util.Log
import com.dootah.DOOTAH_LOG_TAG
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The screen values Dootah carries to a remote implementation.
 *
 * Only the types Dootah can serialise appear here; anything else is refused when
 * the screen is lowered, so this never has to guess. The `Modifier` a caller
 * passed is held separately rather than serialised, because a Compose modifier
 * is a native object with no wire form -- the bundle refers to it by position
 * and the instance stays on this side.
 */
class DootahArguments internal constructor(
    private val names: List<String>,
    private val values: List<Any?>,
    internal val modifier: Modifier,
) {

    /**
     * The arguments as the JSON a bundle reads.
     *
     * Encoded by the same library that parses what comes back, so escaping is
     * not this class's problem: a note title with a quote in it is exactly the
     * input a hand-rolled encoder gets wrong.
     *
     * A value of a type Dootah cannot carry is sent as null rather than
     * stringified. Lowering already refuses those, so reaching this branch means
     * the app and the compiler disagree -- and the bundle, which requires every
     * declared argument, reports it and falls back instead of rendering a screen
     * built from a coerced value.
     */
    fun toJson(): String {

        if (names.isEmpty()) return ""

        return buildJsonObject {
            names.forEachIndexed { index, name ->
                put(name, encode(name, values.getOrNull(index)))
            }
        }.toString()
    }

    private fun encode(name: String, value: Any?): JsonElement = when (value) {

        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)

        // As text. A bundle's numbers are JavaScript doubles, and a Long past
        // 2^53 arrives with its low digits quietly gone.
        is Long -> JsonPrimitive(value.toString())

        else -> {
            Log.e(
                DOOTAH_LOG_TAG,
                "screen argument '$name' is a ${value::class.java.simpleName}, " +
                    "which Dootah cannot carry",
            )
            JsonNull
        }
    }

    companion object {
        internal val EMPTY = DootahArguments(emptyList(), emptyList(), Modifier)
    }
}

/**
 * The screen's callback parameters, by name.
 *
 * A fixed map built at the call site from the composable's own parameters, so
 * the set of reachable callbacks is decided by the APK. A command naming
 * something else finds nothing; there is no lookup by class or method name.
 *
 * Each entry also carries the types that parameter declares, because the values
 * a bundle sends arrive as JSON scalars and have to become the Kotlin types the
 * app's own signature asked for. Coercing against the declaration rather than
 * guessing from the value is what keeps `1` reaching a `(Long) -> Unit` as a
 * `Long` rather than as a `Double` the call would crash on.
 */
class DootahCallbacks internal constructor(
    private val byName: Map<String, DootahCallback>,
) {

    internal fun names(): Set<String> = byName.keys

    /**
     * Runs one callback, and says whether it could.
     *
     * False for a name this screen does not declare and for values that do not
     * fit what it does declare. Both are reported by the caller and dropped: a
     * bundle built against a newer version of a screen has to degrade, never
     * take the app down, and a `ClassCastException` inside someone's `onClick`
     * is exactly the crash this boundary exists to prevent.
     */
    internal fun invoke(name: String, arguments: List<CallbackArgument>): Boolean {

        val callback = byName[name] ?: return false

        val values = callback.coerce(arguments) ?: return false

        @Suppress("UNCHECKED_CAST")
        when (values.size) {
            0 -> (callback.function as Function0<Unit>).invoke()
            1 -> (callback.function as Function1<Any?, Unit>).invoke(values[0])
            2 -> (callback.function as Function2<Any?, Any?, Unit>)
                .invoke(values[0], values[1])
            3 -> (callback.function as Function3<Any?, Any?, Any?, Unit>)
                .invoke(values[0], values[1], values[2])
            else -> return false
        }

        return true
    }

    companion object {
        internal val EMPTY = DootahCallbacks(emptyMap())
    }
}

/**
 * One callback parameter: the function the app passed, and what it takes.
 *
 * The function is held as `Any` because a `(String) -> Unit` and a
 * `(Int, Boolean) -> Unit` have no common supertype worth naming, and because
 * the only thing that may decide how to call one is [parameterTypes] -- which
 * came from the same declaration.
 */
class DootahCallback internal constructor(
    internal val parameterTypes: List<String>,
    internal val function: Any,
) {

    /**
     * Turns what a bundle sent into what this parameter declares, or null.
     *
     * Null rather than a substituted default: a value that does not fit means
     * the bundle and this build of the screen disagree about the signature, and
     * calling anyway with a zero or an empty string would act on a number the
     * developer never wrote.
     */
    internal fun coerce(arguments: List<CallbackArgument>): List<Any?>? {

        if (arguments.size != parameterTypes.size) return null

        return arguments.zip(parameterTypes) { argument, type ->
            argument.asType(type) ?: return null
        }
    }
}

/**
 * One value, read back as the type the parameter declares.
 *
 * A `Long` arrives as text and a `Float` as a number, which is the same split
 * [DootahArguments] uses on the way in, for the same reason: every number inside
 * a bundle is a JavaScript double, and past 2^53 an identifier sent as one comes
 * back with its low digits gone.
 */
private fun CallbackArgument.asType(type: String): Any? = when (type) {

    "String" -> (this as? CallbackArgument.Text)?.value
    "Boolean" -> (this as? CallbackArgument.Bool)?.value
    "Int" -> (this as? CallbackArgument.Number)?.value?.toInt()
    "Float" -> (this as? CallbackArgument.Number)?.value?.toFloat()
    "Double" -> (this as? CallbackArgument.Number)?.value
    "Long" -> when (this) {
        is CallbackArgument.Text -> value.toLongOrNull()
        is CallbackArgument.Number -> value.toLong()
        is CallbackArgument.Bool -> null
    }

    else -> null
}

/**
 * Builds the arguments for a screen with no `Modifier` parameter.
 *
 * [names] is a comma-separated list, and [values] are in the same order. One
 * string plus one vararg rather than alternating pairs, because the names are a
 * compile-time constant and pairing them positionally cannot go half wrong.
 */
@DootahGeneratedApi
fun dootahArguments(names: String, vararg values: Any?): DootahArguments =
    DootahArguments(splitNames(names), values.toList(), Modifier)

/** As [dootahArguments], for a screen that also takes a `Modifier`. */
@DootahGeneratedApi
fun dootahModifiedArguments(
    modifier: Modifier,
    names: String,
    vararg values: Any?,
): DootahArguments = DootahArguments(splitNames(names), values.toList(), modifier)

/**
 * Builds the callback table for one screen.
 *
 * [signatures] pairs with [names] positionally: one entry per callback, each the
 * `|`-separated Kotlin type names that callback takes, empty for a `() -> Unit`.
 * Three parallel compile-time constants rather than a structure, for the same
 * reason the arguments are: nothing here is computed, and positional pairing
 * cannot go half right.
 */
@DootahGeneratedApi
fun dootahCallbacks(
    names: String,
    signatures: String,
    vararg callbacks: Any,
): DootahCallbacks {

    val callbackNames = splitNames(names)
    val parameterTypes = splitSignatures(signatures)

    return DootahCallbacks(
        callbackNames.mapIndexedNotNull { index, name ->
            val function = callbacks.getOrNull(index) ?: return@mapIndexedNotNull null
            name to DootahCallback(
                parameterTypes = parameterTypes.getOrElse(index) { emptyList() },
                function = function,
            )
        }.toMap()
    )
}

private fun splitSignatures(signatures: String): List<List<String>> =
    if (signatures.isEmpty()) emptyList()
    else signatures.split(",").map { entry ->
        if (entry.isEmpty()) emptyList() else entry.split("|")
    }

private fun splitNames(names: String): List<String> =
    if (names.isEmpty()) emptyList() else names.split(",")
